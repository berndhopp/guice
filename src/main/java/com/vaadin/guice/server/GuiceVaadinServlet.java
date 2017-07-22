package com.vaadin.guice.server;

import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;

import com.vaadin.guice.annotation.ForUI;
import com.vaadin.guice.annotation.GuiceUI;
import com.vaadin.guice.annotation.Import;
import com.vaadin.guice.annotation.OverrideBindings;
import com.vaadin.guice.annotation.PackagesToScan;
import com.vaadin.navigator.Navigator;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener;
import com.vaadin.server.DeploymentConfiguration;
import com.vaadin.server.ServiceException;
import com.vaadin.server.SessionInitEvent;
import com.vaadin.server.SessionInitListener;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinServiceInitListener;
import com.vaadin.server.VaadinServlet;
import com.vaadin.server.VaadinServletService;
import com.vaadin.server.VaadinSession;
import com.vaadin.ui.Component;
import com.vaadin.ui.UI;

import org.reflections.Reflections;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.servlet.ServletException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Iterables.concat;
import static com.google.inject.Guice.createInjector;
import static com.google.inject.util.Modules.override;
import static java.lang.reflect.Modifier.isAbstract;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * Subclass of the standard {@link com.vaadin.server.VaadinServlet Vaadin servlet} that adds a
 * {@link GuiceUIProvider} to every new Vaadin session
 *
 * @author Bernd Hopp (bernd@vaadin.com)
 */
public class
GuiceVaadinServlet extends VaadinServlet implements SessionInitListener {

    private final Class<? super Provider<Injector>> injectorProviderType = new TypeLiteral<Provider<Injector>>() {
    }.getRawType();
    private GuiceViewProvider viewProvider;
    private GuiceUIProvider guiceUIProvider;
    private UIScope uiScoper;
    private Provider<VaadinSession> vaadinSessionProvider;
    private Set<Class<? extends UI>> uis;
    private Set<Class<? extends View>> views;
    private Map<Class<? extends UI>, Set<Class<? extends ViewChangeListener>>> viewChangeListeners;
    private Provider<UI> currentUIProvider;
    private Provider<VaadinService> vaadinServiceProvider;
    private Injector injector;
    private VaadinSessionScope vaadinSessionScoper;
    private ViewScope viewScoper;
    private Set<Class<? extends VaadinServiceInitListener>> vaadinServiceInitListeners;

    public GuiceVaadinServlet() {

        final String packagesToScan = getServletConfig().getInitParameter("packagesToScan");

        if (!isNullOrEmpty(packagesToScan)) {
            setup(packagesToScan.split(","));
        } else if (getClass().isAnnotationPresent(PackagesToScan.class)) {
            setup(getClass().getAnnotation(PackagesToScan.class).value());
        } else {
            throw new IllegalStateException("no packagesToScan-initParameter found and no @PackagesToScan-annotation present, please configure the packages to be scanned");
        }
    }

    GuiceVaadinServlet(String[] packagesToScan) {
        setup(packagesToScan);
    }

    GuiceVaadinServlet(Reflections reflections) {
        setup(reflections, getClass().getAnnotations());
    }

    GuiceVaadinServlet(
            Provider<VaadinSession> vaadinSessionProvider,
            Provider<UI> currentUIProvider,
            Provider<View> currentViewProvider,
            Provider<VaadinService> vaadinServiceProvider,
            Reflections reflections,
            Annotation[] annotations
    ) throws ReflectiveOperationException {
        setup(vaadinSessionProvider, currentUIProvider, currentViewProvider, vaadinServiceProvider, reflections, annotations);
    }

    private static Map<Class<? extends UI>, Set<Class<? extends ViewChangeListener>>> getViewChangeListenerClasses(Reflections reflections, Set<Class<? extends UI>> uiClasses) {

        Map<Class<? extends UI>, Set<Class<? extends ViewChangeListener>>> viewChangeListenersByUI = uiClasses
                .stream()
                .collect(toMap(uiClass -> uiClass, uiClass -> new HashSet<>()));

        for (Class<? extends UI> uiClass : uiClasses) {
            viewChangeListenersByUI.put(uiClass, new HashSet<>());
        }

        for (Class<? extends ViewChangeListener> viewChangeListenerClass : reflections.getSubTypesOf(ViewChangeListener.class)) {

            final ForUI annotation = viewChangeListenerClass.getAnnotation(ForUI.class);

            if (annotation == null) {
                viewChangeListenersByUI.values().forEach(listeners -> listeners.add(viewChangeListenerClass));
            } else {
                checkArgument(annotation.value().length > 0, "ForUI#value must contain one ore more UI-classes");

                for (Class<? extends UI> applicableUiClass : annotation.value()) {
                    final Set<Class<? extends ViewChangeListener>> viewChangeListenersForUI = viewChangeListenersByUI.get(applicableUiClass);

                    checkArgument(
                            viewChangeListenersForUI != null,
                            "%s is listed as applicableUi in the @ForUI-annotation of %s, but is not annotated with @GuiceUI"
                    );

                    final Class<? extends Component> viewContainer = applicableUiClass.getAnnotation(GuiceUI.class).viewContainer();

                    checkArgument(!viewContainer.equals(Component.class), "%s is annotated as @ForUI for %s, however viewContainer() is not set in @GuiceUI");

                    viewChangeListenersForUI.add(viewChangeListenerClass);
                }
            }
        }

        return viewChangeListenersByUI;
    }

    private void setup(String[] packagesToScan) {
        Reflections reflections = new Reflections((Object[]) packagesToScan);

        setup(reflections, getClass().getAnnotations());
    }

    //used for non-testing
    private void setup(Reflections reflections, Annotation[] annotations) {
        setup(
                VaadinSession::getCurrent,
                UI::getCurrent,
                () -> {
                    final Navigator navigator = UI.getCurrent().getNavigator();

                    checkState(navigator != null);

                    return navigator.getCurrentView();
                },
                VaadinService::getCurrent,
                reflections,
                annotations
        );
    }

    private void setup(
            Provider<VaadinSession> vaadinSessionProvider,
            Provider<UI> currentUIProvider,
            Provider<View> currentViewProvider,
            Provider<VaadinService> vaadinServiceProvider,
            Reflections reflections,
            Annotation[] annotations
    ) {

        final Set<Module> modulesFromAnnotations = stream(annotations)
                .filter(annotation -> annotation.getClass().isAnnotationPresent(Import.class))
                .map(annotation -> createModule(annotation.getClass().getAnnotation(Import.class).value(), reflections, annotation))
                .collect(toSet());

        final Set<Module> modulesFromPath = loadModulesFromPath(reflections, modulesFromAnnotations);

        Iterable<Module> allModules = concat(
                modulesFromAnnotations,
                modulesFromPath
        );

        List<Module> nonOverrideModules = new ArrayList<>();
        List<Module> overrideModules = new ArrayList<>();

        for (Module module : allModules) {
            if (module.getClass().isAnnotationPresent(OverrideBindings.class)) {
                overrideModules.add(module);
            } else {
                nonOverrideModules.add(module);
            }
        }

        /*
         * combine bindings from the static modules in {@link GuiceVaadinServletConfiguration#modules()} with those bindings
         * from dynamically loaded modules, see {@link RuntimeModule}.
         * This is done first so modules can install their own reflections.
        */
        Module combinedModules = override(nonOverrideModules).with(overrideModules);

        Set<Class<? extends View>> views = reflections.getSubTypesOf(View.class);

        this.uis = reflections.getSubTypesOf(UI.class);
        this.viewChangeListeners = getViewChangeListenerClasses(reflections, uis);
        this.vaadinServiceInitListeners = reflections.getSubTypesOf(VaadinServiceInitListener.class);
        this.vaadinSessionProvider = vaadinSessionProvider;
        this.currentUIProvider = currentUIProvider;
        this.vaadinServiceProvider = vaadinServiceProvider;

        this.views = views;

        this.uiScoper = new UIScope(vaadinSessionProvider, currentUIProvider);
        this.viewScoper = new ViewScope(vaadinSessionProvider, currentViewProvider);
        this.vaadinSessionScoper = new VaadinSessionScope(vaadinSessionProvider);
        this.viewProvider = new GuiceViewProvider(views, this);
        this.guiceUIProvider = new GuiceUIProvider(this);

        //sets up the basic vaadin stuff like UIProvider
        VaadinModule vaadinModule = new VaadinModule(this);

        this.injector = createInjector(vaadinModule, combinedModules);
    }

    @Override
    public void sessionInit(SessionInitEvent event) throws ServiceException {
        // remove DefaultUIProvider instances to avoid mapping
        // extraneous UIs if e.g. a servlet is declared as a nested
        // class in a UI class
        VaadinSession session = event.getSession();

        session
                .getUIProviders()
                .clear();

        //set the GuiceUIProvider
        session.addUIProvider(guiceUIProvider);
    }

    GuiceViewProvider getViewProvider() {
        return viewProvider;
    }

    GuiceUIProvider getGuiceUIProvider() {
        return guiceUIProvider;
    }

    UIScope getUiScoper() {
        return uiScoper;
    }

    Provider<VaadinSession> getVaadinSessionProvider() {
        return vaadinSessionProvider;
    }

    Set<Class<? extends View>> getViews() {
        return views;
    }

    Provider<UI> getCurrentUIProvider() {
        return currentUIProvider;
    }

    Provider<VaadinService> getVaadinServiceProvider() {
        return vaadinServiceProvider;
    }

    Set<Class<? extends UI>> getUis() {
        return uis;
    }

    Set<Class<? extends ViewChangeListener>> getViewChangeListeners(Class<? extends UI> uiClass) {
        return viewChangeListeners.get(uiClass);
    }

    VaadinSessionScope getVaadinSessionScoper() {
        return vaadinSessionScoper;
    }

    ViewScope getViewScoper() {
        return viewScoper;
    }

    Iterator<VaadinServiceInitListener> getServiceInitListeners() {
        return vaadinServiceInitListeners
                .stream()
                .map(injector::getInstance)
                .map(listener -> (VaadinServiceInitListener) listener)
                .iterator();
    }

    @SuppressWarnings("unchecked")
    private Set<Module> loadModulesFromPath(Reflections reflections, Set<Module> modulesFromAnnotations) {
        Set<Class<? extends Module>> modulesFromAnnotationClasses = modulesFromAnnotations
                .stream()
                .map(Module::getClass)
                .collect(toSet());

        return reflections
                .getSubTypesOf(Module.class)
                .stream()
                .filter(moduleClass -> !modulesFromAnnotationClasses.contains(moduleClass))
                .map(moduleClass -> createModule(moduleClass, reflections, null))
                .filter(Objects::nonNull)
                .collect(toSet());
    }

    private Module createModule(Class<? extends Module> moduleClass, Reflections reflections, Annotation annotation) {

        if(isAbstract(moduleClass.getModifiers())){
            return null;
        }

        for (Constructor<?> constructor : moduleClass.getDeclaredConstructors()) {

            Object[] initArgs = new Object[constructor.getParameterCount()];

            Class<?>[] parameterTypes = constructor.getParameterTypes();

            boolean allParameterTypesResolved = true;

            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];

                if (Reflections.class.equals(parameterType)) {
                    initArgs[i] = reflections;
                } else if (injectorProviderType.equals(parameterType)) {
                    initArgs[i] = (Provider<Injector>) this::getInjector;
                } else if (annotation != null && annotation.getClass().equals(parameterType)) {
                    initArgs[i] = annotation;
                } else {
                    allParameterTypesResolved = false;
                    break;
                }
            }

            if (!allParameterTypesResolved) {
                continue;
            }

            constructor.setAccessible(true);

            try {
                return (Module) constructor.newInstance(initArgs);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        throw new IllegalStateException("no suitable constructor found for %s" + moduleClass);
    }

    @Override
    protected void servletInitialized() throws ServletException {
        VaadinService.getCurrent().addSessionInitListener(this);
    }

    @Override
    protected VaadinServletService createServletService(DeploymentConfiguration deploymentConfiguration) throws ServiceException {
        return new GuiceVaadinServletService(this, deploymentConfiguration, this);
    }

    Injector getInjector() {
        return checkNotNull(injector, "injector is not set up yet");
    }
}

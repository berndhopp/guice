package com.vaadin.guice.server;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;

import com.vaadin.guice.annotation.Controller;
import com.vaadin.guice.annotation.ForUI;
import com.vaadin.guice.annotation.Import;
import com.vaadin.guice.annotation.OverrideBindings;
import com.vaadin.guice.annotation.PackagesToScan;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener;
import com.vaadin.server.BootstrapListener;
import com.vaadin.server.DeploymentConfiguration;
import com.vaadin.server.RequestHandler;
import com.vaadin.server.ServiceDestroyListener;
import com.vaadin.server.ServiceException;
import com.vaadin.server.SessionDestroyListener;
import com.vaadin.server.SessionInitEvent;
import com.vaadin.server.SessionInitListener;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinServiceInitListener;
import com.vaadin.server.VaadinServlet;
import com.vaadin.server.VaadinServletService;
import com.vaadin.server.VaadinSession;
import com.vaadin.server.communication.UidlRequestHandler;
import com.vaadin.ui.UI;

import org.reflections.Reflections;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Iterables.concat;
import static com.google.inject.Guice.createInjector;
import static com.google.inject.util.Modules.override;
import static java.lang.reflect.Modifier.isAbstract;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toSet;

/**
 * Subclass of the standard {@link com.vaadin.server.VaadinServlet Vaadin servlet} that adds a
 * {@link GuiceUIProvider} to every new Vaadin session
 *
 * @author Bernd Hopp (bernd@vaadin.com)
 */
@SuppressWarnings("unused")
public class GuiceVaadinServlet extends VaadinServlet implements SessionInitListener {

    private static final Class<? super Provider<Injector>> injectorProviderType = new TypeLiteral<Provider<Injector>>() {
    }.getRawType();
    private final Map<Class<? extends UI>, Set<Class<? extends ViewChangeListener>>> viewChangeListenerCache = new HashMap<>();
    private final Map<Class<? extends UI>, Set<Class<?>>> controllerCache = new HashMap<>();
    private GuiceViewProvider viewProvider;
    private GuiceUIProvider guiceUIProvider;
    private UIScope uiScope;
    private ViewScope viewScope;
    private Injector injector;
    private VaadinSessionScope vaadinSessionScoper;
    private Set<Class<?>> controllerClasses;
    private Set<Class<? extends SessionInitListener>> sessionInitListenerClasses;
    private Set<Class<? extends SessionDestroyListener>> sessionDestroyListenerClasses;
    private Set<Class<? extends ServiceDestroyListener>> serviceDestroyListeners;
    private Set<Class<? extends UI>> uiClasses;
    private Set<Class<? extends View>> viewClasses;
    private Set<Class<? extends ViewChangeListener>> viewChangeListenerClasses;
    private Set<Class<? extends BootstrapListener>> bootStrapListenerClasses;
    private Set<Class<? extends RequestHandler>> requestHandlerClasses;
    private Set<Class<? extends VaadinServiceInitListener>> vaadinServiceInitListenerClasses;
    private Class<? extends UidlRequestHandler> customUidlRequestHandlerClass;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        final String initParameter = servletConfig.getInitParameter("packagesToScan");

        final String[] packagesToScan;

        final boolean annotationPresent = getClass().isAnnotationPresent(PackagesToScan.class);

        if (!isNullOrEmpty(initParameter)) {
            checkState(
                    !annotationPresent,
                    "%s has both @PackagesToScan-annotation and an 'packagesToScan'-initParam",
                    getClass()
            );
            packagesToScan = initParameter.split(",");
        } else if (annotationPresent) {
            packagesToScan = getClass().getAnnotation(PackagesToScan.class).value();
        } else {
            throw new IllegalStateException("no packagesToScan-initParameter found and no @PackagesToScan-annotation present, please configure the packages to be scanned");
        }

        Reflections reflections = new Reflections((Object[]) packagesToScan);

        final Set<Annotation> importAnnotations = stream(getClass().getAnnotations())
                .filter(annotation -> annotation.annotationType().isAnnotationPresent(Import.class))
                .collect(toSet());

        //import packages
        importAnnotations
                .stream()
                .map(annotation -> annotation.annotationType().getAnnotation(Import.class))
                .filter(i -> i.packagesToScan().length != 0)
                .forEach(i -> reflections.merge(new Reflections((Object[]) i.packagesToScan())));

        //import modules
        final Set<Module> modulesFromAnnotations = importAnnotations
                .stream()
                .map(annotation -> createModule(annotation.annotationType().getAnnotation(Import.class).value(), reflections, annotation))
                .collect(toSet());

        Set<Class<? extends Module>> modulesFromAnnotationClasses = modulesFromAnnotations
                .stream()
                .map(Module::getClass)
                .collect(toSet());

        final Set<Module> modulesFromPath = nonAbstractTypes(reflections.getSubTypesOf(Module.class))
                .stream()
                .filter(moduleClass -> !modulesFromAnnotationClasses.contains(moduleClass))
                .map(moduleClass -> createModule(moduleClass, reflections, null))
                .collect(toSet());

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

        this.viewClasses = nonAbstractTypes(reflections.getSubTypesOf(View.class));
        this.uiClasses = nonAbstractTypes(reflections.getSubTypesOf(UI.class));
        this.bootStrapListenerClasses = nonAbstractTypes(reflections.getSubTypesOf(BootstrapListener.class));
        this.vaadinServiceInitListenerClasses = nonAbstractTypes(reflections.getSubTypesOf(VaadinServiceInitListener.class));
        this.requestHandlerClasses = nonAbstractTypes(reflections.getSubTypesOf(RequestHandler.class));
        this.controllerClasses = nonAbstractTypes(reflections.getTypesAnnotatedWith(Controller.class));
        this.sessionInitListenerClasses = nonAbstractTypes(reflections.getSubTypesOf(SessionInitListener.class))
                .stream()
                .filter(cls -> !VaadinServlet.class.isAssignableFrom(cls))
                .collect(toSet());

        this.customUidlRequestHandlerClass = nonAbstractTypes(reflections.getSubTypesOf(UidlRequestHandler.class))
                .stream()
                .filter(cls -> !VaadinServlet.class.isAssignableFrom(cls))
                .findFirst().orElse(null);

        this.sessionDestroyListenerClasses = nonAbstractTypes(reflections.getSubTypesOf(SessionDestroyListener.class));
        this.serviceDestroyListeners = nonAbstractTypes(reflections.getSubTypesOf(ServiceDestroyListener.class));
        this.uiScope = new UIScope();
        this.viewScope = new ViewScope();
        this.vaadinSessionScoper = new VaadinSessionScope();
        this.viewProvider = new GuiceViewProvider(this);
        this.guiceUIProvider = new GuiceUIProvider(this);

        this.viewChangeListenerClasses = nonAbstractTypes(reflections.getSubTypesOf(ViewChangeListener.class));

        //sets up the basic vaadin stuff like UISetup
        VaadinModule vaadinModule = new VaadinModule(this);

        this.injector = createInjector(vaadinModule, combinedModules);

        super.init(servletConfig);
    }

    private <U> Set<Class<? extends U>> nonAbstractTypes(Set<Class<? extends U>> types) {
        return types
                .stream()
                .filter(t -> !isAbstract(t.getModifiers()))
                .collect(toSet());
    }

    @Override
    protected void servletInitialized() {
        final VaadinService vaadinService = VaadinService.getCurrent();

        vaadinService.addSessionInitListener(this);

        sessionInitListenerClasses
                .stream()
                .map(getInjector()::getInstance)
                .forEach(vaadinService::addSessionInitListener);

        sessionDestroyListenerClasses
                .stream()
                .map(getInjector()::getInstance)
                .forEach(vaadinService::addSessionDestroyListener);

        serviceDestroyListeners
                .stream()
                .map(getInjector()::getInstance)
                .forEach(vaadinService::addServiceDestroyListener);
    }

    @Override
    protected VaadinServletService createServletService(DeploymentConfiguration deploymentConfiguration) throws ServiceException {
        return new GuiceVaadinServletService(this, deploymentConfiguration);
    }

    @Override
    public void sessionInit(SessionInitEvent event) {
        VaadinSession session = event.getSession();

        // remove UIProvider instances to avoid mapping
        // extraneous UIs if e.g. a servlet is declared as a nested
        // class in a UI class
        session.getUIProviders().forEach(session::removeUIProvider);

        //set the GuiceUIProvider
        session.addUIProvider(guiceUIProvider);

        bootStrapListenerClasses
                .stream()
                .map(getInjector()::getInstance)
                .forEach(session::addBootstrapListener);

        requestHandlerClasses
                .stream()
                .map(getInjector()::getInstance)
                .forEach(session::addRequestHandler);
    }

    GuiceViewProvider getViewProvider() {
        return viewProvider;
    }

    GuiceUIProvider getGuiceUIProvider() {
        return guiceUIProvider;
    }

    UIScope getUiScope() {
        return uiScope;
    }

    Set<Class<? extends View>> getViewClasses() {
        return viewClasses;
    }

    Set<Class<? extends UI>> getUiClasses() {
        return uiClasses;
    }

    VaadinSessionScope getVaadinSessionScoper() {
        return vaadinSessionScoper;
    }

    Iterator<VaadinServiceInitListener> getServiceInitListeners() {
        return vaadinServiceInitListenerClasses
                .stream()
                .map(key -> (VaadinServiceInitListener) getInjector().getInstance(key))
                .iterator();
    }

    public UidlRequestHandler getCustomUidlRequestHandlerClass() {
        if (null == customUidlRequestHandlerClass) {
            return null;
        }
        return getInjector().getInstance(customUidlRequestHandlerClass);
    }

    Set<Class<? extends ViewChangeListener>> getViewChangeListeners(Class<? extends UI> uiClass) {
        return viewChangeListenerCache.computeIfAbsent(uiClass, u -> getApplicable(u, viewChangeListenerClasses));
    }

    Set<Class<?>> getControllerClasses(Class<? extends UI> uiClass) {
        return controllerCache.computeIfAbsent(uiClass, uic -> getApplicableControllers(uic, controllerClasses));
    }

    Set<Class<?>> getControllerClasses() {
        return controllerClasses;
    }

    private <T> Set<Class<? extends T>> getApplicableControllers(Class<? extends UI> uiClass, Set<Class<? extends T>> classes) {
        return ImmutableSet.copyOf(
                classes
                        .stream()
                        .filter(t -> t.getAnnotation(Controller.class).value().equals(uiClass))
                        .iterator()
        );
    }

    private <T> Set<Class<? extends T>> getApplicable(Class<? extends UI> uiClass, Set<Class<? extends T>> classes) {
        return ImmutableSet.copyOf(
                classes
                        .stream()
                        .filter(t -> appliesForUI(uiClass, t))
                        .iterator()
        );
    }

    boolean appliesForUI(Class<? extends UI> uiClass, Class<?> clazz) {

        checkState(uiClasses.contains(uiClass), "ui class not registered: %s", uiClass);

        final ForUI forUI = clazz.getAnnotation(ForUI.class);

        if (forUI == null) {
            return true;
        }

        final List<Class<? extends UI>> applicableUIs = asList(forUI.value());

        checkArgument(!applicableUIs.isEmpty(), "@ForUI#value() must not be empty at %s", uiClass);

        return applicableUIs.contains(uiClass);
    }

    private Module createModule(Class<? extends Module> moduleClass, Reflections reflections, Annotation annotation) {

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
                } else if (annotation != null && annotation.annotationType().equals(parameterType)) {
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

    protected Injector getInjector() {
        return checkNotNull(injector, "injector is not set up yet");
    }

    public ViewScope getViewScope() {
        return viewScope;
    }
}

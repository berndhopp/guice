package com.vaadin.guice.server;

import com.google.common.base.Optional;
import com.google.inject.Module;

import com.vaadin.guice.annotation.GuiceUI;
import com.vaadin.guice.annotation.GuiceView;
import com.vaadin.guice.annotation.GuiceViewChangeListener;
import com.vaadin.guice.annotation.ModuleToCreate;
import com.vaadin.guice.annotation.UIModule;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener;
import com.vaadin.ui.Component;
import com.vaadin.ui.UI;

import org.reflections.Reflections;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

final class ReflectionUtils {

    private ReflectionUtils() {
    }

    static Collection<Module> getModulesFromAnnotations(Annotation[] annotations, Reflections reflections, GuiceVaadin guiceVaadin)
            throws IllegalAccessException, InstantiationException, InvocationTargetException {
        Collection<Module> modules = new ArrayList<Module>();

        for (Annotation annotation : annotations) {
            final Module module = createIfModuleToCreate(annotation);

            if (module != null) {
                postProcess(reflections, guiceVaadin, module);
                modules.add(module);
            }
        }

        return modules;
    }

    @SuppressWarnings("unchecked")
    private static Module createIfModuleToCreate(Annotation annotation) throws IllegalAccessException, InvocationTargetException, InstantiationException {

        checkNotNull(annotation);

        final Class<? extends Annotation> annotationClass = annotation.getClass();

        if (!annotationClass.isAnnotationPresent(ModuleToCreate.class)) {
            return null;
        }

        final ModuleToCreate moduleToCreate = annotationClass.getAnnotation(ModuleToCreate.class);

        try {
            final Class<? extends Module> moduleClass = moduleToCreate.value();

            final Constructor<? extends Module> constructorWithAnnotation = moduleClass.getConstructor(annotationClass);

            if (constructorWithAnnotation != null) {
                constructorWithAnnotation.setAccessible(true);
                return constructorWithAnnotation.newInstance(annotation);
            }

            final Constructor<? extends Module> defaultConstructor = moduleClass.getConstructor();

            if (defaultConstructor != null) {
                defaultConstructor.setAccessible(true);
                return defaultConstructor.newInstance();
            }

            throw new IllegalArgumentException("no suitable constructor found for " + moduleClass);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Module createIfModuleToCreate(Class<? extends Module> type, Reflections reflections, final GuiceVaadin guiceVaadin) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        final Module module = type.newInstance();

        postProcess(reflections, guiceVaadin, module);

        return module;
    }

    private static void postProcess(Reflections reflections, GuiceVaadin guiceVaadin, Module module) {
        if (module instanceof NeedsReflections) {
            ((NeedsReflections) module).setReflections(reflections);
        }

        if (module instanceof NeedsInjector) {
            ((NeedsInjector) module).setInjectorProvider(guiceVaadin);
        }
    }

    static List<Module> getStaticModules(Class<? extends Module>[] modules, Reflections reflections, GuiceVaadin guiceVaadin) throws IllegalAccessException, InstantiationException, InvocationTargetException {
        List<Module> hardWiredModules = new ArrayList<Module>(modules.length);

        for (Class<? extends Module> moduleClass : modules) {
            hardWiredModules.add(createIfModuleToCreate(moduleClass, reflections, guiceVaadin));
        }
        return hardWiredModules;
    }

    @SuppressWarnings("unchecked")
    static Set<Module> getDynamicModules(Reflections reflections, GuiceVaadin guiceVaadin) throws IllegalAccessException, InstantiationException, InvocationTargetException {
        Set<Module> dynamicallyLoadedModules = new HashSet<Module>();

        for (Class<?> dynamicallyLoadedModuleClass : reflections.getTypesAnnotatedWith(UIModule.class, true)) {
            checkArgument(
                    Module.class.isAssignableFrom(dynamicallyLoadedModuleClass),
                    "class %s is annotated with @UIModule but does not implement com.google.inject.Module",
                    dynamicallyLoadedModuleClass
            );

            dynamicallyLoadedModules.add(createIfModuleToCreate((Class<? extends Module>) dynamicallyLoadedModuleClass, reflections, guiceVaadin));
        }
        return dynamicallyLoadedModules;
    }

    @SuppressWarnings("unchecked")
    static Set<Class<? extends View>> getGuiceViewClasses(Reflections reflections) {
        Set<Class<? extends View>> views = new HashSet<Class<? extends View>>();

        for (Class<?> viewClass : reflections.getTypesAnnotatedWith(GuiceView.class)) {
            checkArgument(
                    View.class.isAssignableFrom(viewClass),
                    "class %s is annotated with @GuiceView but does not implement com.vaadin.navigator.View",
                    viewClass
            );

            views.add((Class<? extends View>) viewClass);
        }
        return views;
    }

    @SuppressWarnings("unchecked")
    static Set<Class<? extends UI>> getGuiceUIClasses(Reflections reflections) {
        Set<Class<? extends UI>> uis = new HashSet<Class<? extends UI>>();

        for (Class<?> uiClass : reflections.getTypesAnnotatedWith(GuiceUI.class)) {
            checkArgument(
                    UI.class.isAssignableFrom(uiClass),
                    "class %s is annotated with @GuiceUI but does not extend com.vaadin.UI",
                    uiClass
            );

            uis.add((Class<? extends UI>) uiClass);
        }
        return uis;
    }

    @SuppressWarnings("unchecked")
    static Map<Class<? extends UI>, Set<Class<? extends ViewChangeListener>>> getViewChangeListenerClasses(Reflections reflections, Set<Class<? extends UI>> uiClasses) {

        Map<Class<? extends UI>, Set<Class<? extends ViewChangeListener>>> viewChangeListenersByUI = new HashMap<Class<? extends UI>, Set<Class<? extends ViewChangeListener>>>(uiClasses.size());

        final Set<Class<?>> allViewChangeListenerClasses = reflections.getTypesAnnotatedWith(GuiceViewChangeListener.class, true);

        for (Class<? extends UI> uiClass : uiClasses) {
            viewChangeListenersByUI.put(uiClass, new HashSet<Class<? extends ViewChangeListener>>());
        }

        for (Class<?> viewChangeListenerClass : allViewChangeListenerClasses) {
            checkArgument(
                    ViewChangeListener.class.isAssignableFrom(viewChangeListenerClass),
                    "class %s is annotated with @GuiceViewChangeListener but does not implement com.vaadin.navigator.ViewChangeListener",
                    viewChangeListenerClass
            );

            final GuiceViewChangeListener annotation = viewChangeListenerClass.getAnnotation(GuiceViewChangeListener.class);

            if (annotation.applicableUIs().length == 0) {
                for (Set<Class<? extends ViewChangeListener>> viewChangeListenersForUI : viewChangeListenersByUI.values()) {
                    viewChangeListenersForUI.add((Class<? extends ViewChangeListener>) viewChangeListenerClass);
                }
            } else {
                for (Class<? extends UI> applicableUiClass : annotation.applicableUIs()) {
                    final Set<Class<? extends ViewChangeListener>> viewChangeListenersForUI = viewChangeListenersByUI.get(applicableUiClass);

                    checkArgument(
                            viewChangeListenersForUI != null,
                            "%s is listed as applicableUi in the @GuiceViewChangeListener-annotation of %s, but is not annotated with @GuiceUI"
                    );

                    final boolean viewContainerSet = !applicableUiClass.getAnnotation(GuiceUI.class).viewContainer().equals(Component.class);

                    checkArgument(viewContainerSet, "%s is annotated as @GuiceViewChangeListener for %s, however viewContainer() is not set in @GuiceUI");

                    viewChangeListenersForUI.add((Class<? extends ViewChangeListener>) viewChangeListenerClass);
                }
            }
        }

        return viewChangeListenersByUI;
    }

    static Optional<Class<? extends View>> findErrorView(Iterable<Class<? extends View>> viewClasses) {

        Class<? extends View> errorView = null;

        for (Class<? extends View> viewClass : viewClasses) {
            GuiceView annotation = viewClass.getAnnotation(GuiceView.class);

            checkState(annotation != null);

            if (annotation.isErrorView()) {
                checkState(
                        errorView == null,
                        "%s and %s have an @GuiceView-annotation with isErrorView set to true",
                        errorView,
                        viewClass
                );

                errorView = viewClass;
            }
        }

        return Optional.<Class<? extends View>>fromNullable(errorView);
    }
}


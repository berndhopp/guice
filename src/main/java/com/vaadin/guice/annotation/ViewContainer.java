/*
 * Copyright 2015 The original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vaadin.guice.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Mark a View as UI's default view for navigation with this Annotation. DefaultViews are the views
 * used by a UI's {@link com.vaadin.navigator.Navigator}.
 * <pre>
 *      <code>
 * {@literal @}GuiceUI
 * public class MyUI extends UI {
 * {@literal @}Inject
 * {@literal @}ViewContainer
 * private MyDefaultView myDefaultView;
 *
 * {@literal @}Inject
 * private MyMainComponent myMainComponent;
 *
 * {@literal @}Override
 * protected void init(VaadinRequest vaadinRequest) {
 * setContent(myMainComponent);
 * }
 * }
 *      </code>
 *      <code>
 * {@literal @}UIScope
 * public class MyMainComponent extends VerticalLayout {
 * {@literal @}Inject
 * MyMainComponent(MyDefaultView myDefaultView){
 * addComponent(myDefaultView);
 * }
 * }
 *      </code>
 *  </pre>
 *
 * @author Bernd Hopp (bernd@vaadin.com)
 */
@Target({ElementType.FIELD})
@Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@Documented
public @interface ViewContainer {
}
package com.vaadin.guice.server;

import com.vaadin.server.DeploymentConfiguration;
import com.vaadin.server.ServiceException;
import com.vaadin.server.VaadinServiceInitListener;
import com.vaadin.server.VaadinServletService;

import java.util.Iterator;

import static com.google.common.collect.Iterators.concat;

class GuiceVaadinServletService extends VaadinServletService {

    private final GuiceVaadinServlet guiceVaadinServlet;

    GuiceVaadinServletService(GuiceVaadinServlet servlet, DeploymentConfiguration deploymentConfiguration) throws ServiceException {
        super(servlet, deploymentConfiguration);
        this.guiceVaadinServlet = servlet;
        this.init();
    }

    @Override
    protected Iterator<VaadinServiceInitListener> getServiceInitListeners() {
        return concat(super.getServiceInitListeners(), guiceVaadinServlet.getServiceInitListeners());
    }
}

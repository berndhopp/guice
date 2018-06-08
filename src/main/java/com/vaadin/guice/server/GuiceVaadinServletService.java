package com.vaadin.guice.server;

import com.vaadin.server.*;
import com.vaadin.server.communication.UidlRequestHandler;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

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

    @Override
    protected List<RequestHandler> createRequestHandlers() throws ServiceException {
        UidlRequestHandler customRequestHandler = guiceVaadinServlet.getCustomUidlRequestHandlerClass();
        if (null == customRequestHandler) {
            return super.createRequestHandlers();
        }

        return super.createRequestHandlers().stream().map(handler -> {
            if (handler instanceof UidlRequestHandler) {
                return customRequestHandler;
            }
            return handler;
        }).collect(Collectors.toList());
    }
}

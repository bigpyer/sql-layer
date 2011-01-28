/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.cserver.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.akiban.cserver.CServer;
import com.akiban.cserver.service.config.ConfigurationService;
import com.akiban.cserver.service.jmx.JmxManageable;
import com.akiban.cserver.service.jmx.JmxRegistryService;
import com.akiban.cserver.service.memcache.MemcacheService;
import com.akiban.cserver.service.session.SessionService;
import com.akiban.cserver.service.tree.TreeService;
import com.akiban.cserver.store.SchemaManager;
import com.akiban.cserver.store.Store;

public class ServiceManagerImpl implements ServiceManager, JmxManageable
{
    private static final AtomicReference<ServiceManager> instance = new AtomicReference<ServiceManager>(null);
    // TODO: Supply the factory externally.
    private final ServiceFactory factory;
    private Map<Class<?>, Service<?>> services; // for each key-val, the ? should be the same; (T.class -> Service<T>)

    private final CountDownLatch blockerLatch = new CountDownLatch(1);

    public static void setServiceManager(ServiceManager newInstance)
    {
        if (newInstance == null) {
            instance.set(null);
        }
        else if (!instance.compareAndSet(null, newInstance)) {
            throw new RuntimeException("Tried to install a ServiceManager, but one was already set");
        }
    }

    /**
     * This constructor is made protected for unit testing.
     * @param factory the factory that creates the services this instance manages
     */
    public ServiceManagerImpl(ServiceFactory factory)
    {
        this.factory = factory;
        services = new LinkedHashMap<Class<?>, Service<?>>();
    }

    public static ServiceManager get()
    {
        return instance.get();
    }
    
    @Override
    public ConfigurationService getConfigurationService() {
        return getService(ConfigurationService.class);
    }

    @Override
    public CServer getCServer() {
        return getService(CServer.class);
    }

    @Override
    public Store getStore() {
        return getService(Store.class);
    }

    @Override
    public SessionService getSessionService() {
        return getService(SessionService.class);
    }
    
    @Override
    public TreeService getTreeService() {
        return getService(TreeService.class);
    }

    @Override
    public MemcacheService getMemcacheService() {
        return getService(MemcacheService.class);
    }

    @Override
    public SchemaManager getSchemaManager() {
        return getService(SchemaManager.class);
    }

    public void startServices() throws Exception {

        Service<JmxRegistryService> jmxRegistryService = factory.jmxRegistryService();
        JmxRegistryService jmxRegistry = jmxRegistryService.cast();
        
        startAndPut(jmxRegistryService, jmxRegistry);
        startAndPut(factory.configurationService(), jmxRegistry);

        jmxRegistry.register(this);
        ConfigurationService configService = getServiceAsService(ConfigurationService.class).cast();
        servicesDebugHooks(configService);
        
        // TODO -
        // Temporarily I moved this so that services can refer to their predecessors
        // during their start() methods.  I think this is natural and appropriate, but
        // it also means the order of the following method calls is fragile.
        // I'd like to brainstorm a better approach. -- Peter
        
        setServiceManager(this);
        startAndPut(factory.sessionService(), jmxRegistry);
        startAndPut(factory.treeService(), jmxRegistry);
        startAndPut(factory.schemaManager(), jmxRegistry);
        startAndPut(factory.storeService(), jmxRegistry);
        startAndPut(factory.networkService(), jmxRegistry);
        startAndPut(factory.chunkserverService(), jmxRegistry);
        startAndPut(factory.memcacheService(), jmxRegistry);
        afterStart();
    }

    private void servicesDebugHooks(ConfigurationService configService)
    throws InterruptedException
    {
        if (configService.getProperty("services", "start_blocked", "false").equalsIgnoreCase("true")) {
            System.out.println("BLOCKING BLOCKING BLOCKING BLOCKING BLOCKING");
            System.out.println("  CServer is waiting for persmission to");
            System.out.println("  proceed from JMX.");
            System.out.println("BLOCKING BLOCKING BLOCKING BLOCKING BLOCKING");
            blockerLatch.await();
        }
        else {
            blockerLatch.countDown();
        }
    }

    private void startAndPut(Service service, JmxRegistryService jmxRegistry) throws Exception {
        //System.out.println("Starting service: " + service.getClass()); // TODO change to logging
        service.start();
        Service<?> old = services.put(service.castClass(), service);
        if (old != null) {
            services.put(service.castClass(), old);
            throw new RuntimeException(String.format("Conflicting services: %s (%s) would bump %s (%s)",
                    service.getClass(), service.castClass(), old.getClass(), old.castClass()));
        }
        if (service instanceof JmxManageable) {
            jmxRegistry.register((JmxManageable) service);
        }
    }

    public void stopServices() throws Exception {
        setServiceManager(null);
        List<Service> stopServices = new ArrayList<Service>(services.size());
        for (Service service : services.values()) {
            stopServices.add(service);
        }
        //System.out.println("Preparing to shut down services: " + stopServices); // TODO change to logging
        ListIterator<Service> reverseIter = stopServices
                .listIterator(stopServices.size());
        List<Exception> exceptions = new ArrayList<Exception>();
        while (reverseIter.hasPrevious()) {
            try {
                Service service = reverseIter.previous();
                //System.out.println("Shutting down service: " + service.getClass()); // TODO change to logging
                service.stop();
            } catch (Exception t) {
                exceptions.add(t);
            }
        }
        if (!exceptions.isEmpty()) {
            if (exceptions.size() == 1) {
                throw exceptions.get(0);
            }
            throw new Exception("Failure(s) while shutting down services: " + exceptions, exceptions.get(0));
        }
    }
    
    // TODO - Review this.
    // Need this to construct an AIS instance.  Both SchemaService
    // and StoreService need to be started and registered in the
    // services map before calling getAis().  There is no logical
    // successor service to call this from, so I put the
    // AfterStart interface back any.  Any alternative solution
    // would also be fine.
    //
    private void afterStart() throws Exception {
        for (final Service<?> service : services.values()) {
            if (service instanceof AfterStart) {
                ((AfterStart)service).afterStart();
            }
        }
    }

    private <T> T getService(Class<T> ofClass) {
        Service<T> serviceT = getServiceAsService(ofClass);
        if (serviceT == null) {
            throw new ServiceNotStartedException(ofClass.getName());
        }
        return serviceT.cast();
    }

    private <T> Service<T> getServiceAsService(Class<T> ofClass) {
        final Service<?> service = services.get(ofClass);
        if (service == null) {
            return null;
        }
        final Object asObject = service.cast();
        if (!ofClass.isInstance(asObject)) {
            Class<?> actualClass = asObject == null ? null : asObject.getClass();
            throw new RuntimeException(
                    String.format("%s expected to be of class %s, was %s", asObject, ofClass, actualClass));
        }
        @SuppressWarnings("unchecked") final Service<T> serviceT = (Service<T>) service;
        assert serviceT.castClass().equals(ofClass) : String.format("%s != %s", serviceT.castClass(), ofClass);
        return serviceT;
    }

    @Override
    public JmxObjectInfo getJmxObjectInfo() {
        return new JmxObjectInfo("Services", this, ServiceManagerMXBean.class);
    }

    @Override
    public boolean isStartupBlocked() {
        return blockerLatch.getCount() > 0;
    }

    @Override
    public void resumeStartup() {
        blockerLatch.countDown();
    }

    /**
     * Quick-and-dirty testing tool. Creates a ServiceManager, starts its
     * services (including JMX), and then just sits around waiting to be killed.
     * 
     * @param ignored
     *            ignored
     */
    public static void main(String[] ignored) throws Exception {
        final DefaultServiceFactory serviceFactory = new DefaultServiceFactory();
        ServiceManager sm = new ServiceManagerImpl(serviceFactory);
        sm.startServices();
        Object foo = new Object();
        synchronized (foo) {
            try {
                foo.wait();
            } catch (InterruptedException e) {
                System.err.println("Not sure how we got interrupted!");
                e.printStackTrace();
            }
        }
    }
}

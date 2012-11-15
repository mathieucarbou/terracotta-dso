/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.impl;

import com.tc.objectserver.api.EvictionTrigger;
import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.Sink;
import com.tc.l2.objectserver.ServerTransactionFactory;
import com.tc.lang.TCThreadGroup;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.object.ObjectID;
import com.tc.objectserver.api.EvictableMap;
import com.tc.objectserver.api.ObjectManager;
import com.tc.objectserver.api.ServerMapEvictionManager;
import com.tc.objectserver.api.ShutdownError;
import com.tc.objectserver.context.ServerMapEvictionContext;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.core.api.ManagedObjectState;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.objectserver.l1.impl.ClientObjectReferenceSet;
import com.tc.objectserver.persistence.PersistentCollectionsUtil;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.runtime.MemoryEventsListener;
import com.tc.runtime.MemoryUsage;
import com.tc.text.PrettyPrinter;
import com.tc.util.ObjectIDSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.terracotta.corestorage.monitoring.MonitoredResource;

/**
 *
 * @author mscott
 */
public class ProgressiveEvictionManager implements ServerMapEvictionManager {

    private static final TCLogger logger = TCLogging
            .getLogger(ProgressiveEvictionManager.class);
    private static final int SLEEP_TIME = 60;
    private static final int L2_CACHEMANAGER_RESOURCEPOLLINGINTERVAL = TCPropertiesImpl
            .getProperties()
            .getInt(TCPropertiesConsts.L2_CACHEMANAGER_RESOURCEPOLLINGINTERVAL, SLEEP_TIME);
      private final static boolean                PERIODIC_EVICTOR_ENABLED        = TCPropertiesImpl
                                                                                  .getProperties()
                                                                                  .getBoolean(TCPropertiesConsts.EHCACHE_STORAGESTRATEGY_DCV2_PERIODICEVICTION_ENABLED, true);
    private static final int L2_CACHEMANAGER_CRITICALTHRESHOLD = TCPropertiesImpl
            .getProperties()
            .getInt(TCPropertiesConsts.L2_CACHEMANAGER_CRITICALTHRESHOLD, 90);
    private final ServerMapEvictionEngine evictor;
    private final ResourceMonitor trigger;
    private final PersistentManagedObjectStore store;
    private final ObjectManager objectManager;
    private final ClientObjectReferenceSet clientObjectReferenceSet;
    private Sink evictorSink;
    private final ExecutorService agent;
    private ThreadGroup        evictionGrp;
    private final Responder responder =        new Responder();
    private volatile           boolean   isEmergency = false;
    
    private final static Future<Void> completedFuture = new Future<Void>() {

            @Override
            public boolean cancel(boolean bln) {
                return true;
            }

            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public Void get() throws InterruptedException, ExecutionException {
                return null;
            }

            @Override
            public Void get(long l, TimeUnit tu) throws InterruptedException, ExecutionException, TimeoutException {
                return null;
            }
            
        };

    public ProgressiveEvictionManager(ObjectManager mgr, MonitoredResource monitored, PersistentManagedObjectStore store, ClientObjectReferenceSet clients, ServerTransactionFactory trans, final TCThreadGroup grp) {
        this.objectManager = mgr;
        this.store = store;
        this.clientObjectReferenceSet = clients;
        this.evictor = new ServerMapEvictionEngine(mgr, trans);
        //  assume 100 MB/sec fill rate and set 0% usage poll rate to the time it would take to fill up.
        this.evictionGrp = new ThreadGroup(grp, "Eviction Group");
        this.trigger = new ResourceMonitor(monitored, (monitored.getTotal() * 1000) / (100 * 1024 * 1024), evictionGrp);      
        this.agent = new ThreadPoolExecutor(1, 64, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),new ThreadFactory() {
            private int count = 1;
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(evictionGrp, r, "Expiration Thread - " + count++);
            }
        });
        log("critical threshold " + L2_CACHEMANAGER_CRITICALTHRESHOLD);
    }

    @Override
    public void initializeContext(final ConfigurationContext context) {
        evictor.initializeContext(context);
        final ServerConfigurationContext scc = (ServerConfigurationContext) context;
        this.evictorSink = scc.getStage(ServerConfigurationContext.SERVER_MAP_EVICTION_PROCESSOR_STAGE).getSink();
    }

    @Override
    public void startEvictor() {
        evictor.startEvictor();
        trigger.registerForMemoryEvents(responder);
    }

    @Override
    public void runEvictor() {
            scheduleEvictionRun();
    }
    
    private Future<Void> scheduleEvictionRun() {
        try{
            final ObjectIDSet evictableObjects = store.getAllEvictableObjectIDs();
            return agent.submit(new Callable<Void>() {

                @Override
                public Void call() throws Exception {
                    for (final ObjectID mapID : evictableObjects) {
                        if ( Thread.interrupted() ) {
                            return null;
                        }
                        doEvictionOn(new PeriodicEvictionTrigger(ProgressiveEvictionManager.this, objectManager, mapID, evictor.isElementBasedTTIorTTL()));
                    }
                    return null;
                }

            });
        } catch ( ShutdownError err ) {
            //  is probably in shutodown, unregister
            trigger.unregisterForMemoryEvents(responder);
        }
        agent.shutdown();
        return completedFuture;
    }
    
    public void scheduleEvictionTrigger(final EvictionTrigger trigger) {    
        agent.submit(new Runnable() {
            @Override
            public void run()  {
                doEvictionOn(trigger);
            }
        });        
    }
    /*
     * return of false means the map is gone
     */

    @Override
    public boolean doEvictionOn(final EvictionTrigger trigger) {        
        if ( Thread.currentThread().getThreadGroup() != this.evictionGrp ) {
            scheduleEvictionTrigger(trigger);
            return true;
        }
        
        ObjectID oid = trigger.getId();

        if (!evictor.markEvictionInProgress(oid)) {
            return true;
        }

        final ManagedObject mo = this.objectManager.getObjectByIDOrNull(oid);
        try {
            if (mo == null) {
                if (evictor.isLogging()) {
                    log("Managed object gone : " + oid);
                }
                return false;
            }

            final ManagedObjectState state = mo.getManagedObjectState();
            final String className = state.getClassName();

            EvictableMap ev = getEvictableMapFrom(mo.getID(), state);
    // if size is zero or cache is pinned or already evicting, exit false
            if ( ev.getSize() == 0 ||
                 ev.getMaxTotalCount() == 0 ||
                 !trigger.startEviction(ev) ) {
                this.objectManager.releaseReadOnly(mo);
                return true;
            }
            ServerMapEvictionContext context = doEviction(trigger, ev, className, ev.getCacheName());

            // Reason for releasing the checked-out object before adding the context to the sink is that we can block on add
            // to the sink because the sink reached max capacity and blocking
            // with a checked-out object will result in a deadlock. @see DEV-5207
            trigger.completeEviction(ev);
            if ( context == null && ev.isEvicting() ) {
                throw new AssertionError(trigger.toString());
            }
            this.objectManager.releaseReadOnly(mo);
            
            if (context != null && !Thread.currentThread().isInterrupted()) {
                this.evictorSink.add(context);
            } 
        } finally {
            evictor.markEvictionDone(oid);
            if (evictor.isLogging()) {
                log("Evictor results " + trigger);
            }
        }

        return true;
    }

    private ServerMapEvictionContext doEviction(final EvictionTrigger trigger, final EvictableMap ev,
            final String className,
            final String cacheName) {
        int max = ev.getMaxTotalCount();

        if (max < 0) {
//  cache has no count capacity max is MAX_VALUE;
            max = Integer.MAX_VALUE;
        }

        Map samples = trigger.collectEvictonCandidates(max, ev, clientObjectReferenceSet);

        if (samples.isEmpty()) {
            return null;
        } else {
            return new ServerMapEvictionContext(trigger, samples, className, cacheName);
        }
    }

    List<Future<Void>> emergencyEviction(final boolean blowout) {
        final ObjectIDSet evictableObjects = store.getAllEvictableObjectIDs();
        ArrayList<Future<Void>> push = new ArrayList<Future<Void>>(evictableObjects.size());
        Random r = new Random();
        List<ObjectID> list = new ArrayList<ObjectID>(evictableObjects);
        while ( !list.isEmpty() ) {
            final ObjectID mapID = list.remove(r.nextInt(list.size()));
            push.add(agent.submit(new Callable<Void>() {
                public Void call() {
                    if ( Thread.interrupted() ) {
                        return null;
                    }
                    EmergencyEvictionTrigger trigger = new EmergencyEvictionTrigger(objectManager, mapID , blowout);
                    doEvictionOn(trigger);
                    return null;
                }
            }));
        }
        return push;
    }

    private EvictableMap getEvictableMapFrom(final ObjectID id, final ManagedObjectState state) {
        if (!PersistentCollectionsUtil.isEvictableMapType(state.getType())) {
            throw new AssertionError(
                    "Received wrong object thats not evictable : "
                    + id + " : "
                    + state);
        }
        return (EvictableMap) state;
    }

    private void log(String msg) {
        logger.info(msg);
    }

    @Override
    public void evict(ObjectID oid, Map samples, String className, String cacheName) {
        evictor.evictFrom(oid, samples, className, cacheName);
        if (evictor.isLogging() && !samples.isEmpty()) {
            log("Evicted: " + samples.size() + " from: " + className + "/" + cacheName);
        }
    }

    @Override
    public PrettyPrinter prettyPrint(PrettyPrinter out) {
        return evictor.prettyPrint(out);
    }

    class Responder implements MemoryEventsListener {

        long last = System.currentTimeMillis();
        int size = 0;

        List<Future<Void>> currentRun = Collections.singletonList(completedFuture);
        
        public void cancelCurrentRun(boolean immediate) {
            for ( Future<Void> n : currentRun ) {
                n.cancel(immediate);
            }
        }
        
        public boolean isDone() {
            for ( Future<Void> n : currentRun ) {
                if ( !n.isDone() ) return false;
            }
            return true;
        }

        @Override
        public void memoryUsed(MemoryUsage usage, boolean recommendOffheap) {
            try {
                int percent = usage.getUsedPercentage();
                long current = System.currentTimeMillis();
                if (evictor.isLogging()) {
                    log("Percent usage:" + percent + " time:" + (current - last) + " msec., size delta:" + (percent - size));
                }
                if (percent > L2_CACHEMANAGER_CRITICALTHRESHOLD) {
                    boolean blowout = isEmergency; // if already in emergency situation, really try hard to remove items.
                    
                    if ( !isEmergency || isDone() ) {
                        log("Emergency Triggered - " + percent);
                        cancelCurrentRun(true);
                        currentRun = emergencyEviction(blowout);
                        isEmergency = true;
                    }
                } else {
                    clientObjectReferenceSet.size();
                    if ( isEmergency ) {
                        isEmergency = false;
                        cancelCurrentRun(true);
                    }
                    if ( PERIODIC_EVICTOR_ENABLED && isDone() ) {
                        currentRun = Collections.singletonList(scheduleEvictionRun());
                    } 
                }
                last = current;
                size = percent;
            } catch (UnsupportedOperationException us) {
                if ( isDone() ) {
                    currentRun = Collections.singletonList(scheduleEvictionRun());
                }
                log(us.toString());
            }
        }
    }
}

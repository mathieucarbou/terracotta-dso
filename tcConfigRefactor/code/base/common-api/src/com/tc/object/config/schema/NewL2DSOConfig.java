/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.config.schema;

import com.tc.config.schema.NewConfig;
import com.terracottatech.config.BindPort;
import com.terracottatech.config.Offheap;

/**
 * Represents all configuration read by the DSO L2 and which is independent of application.
 */
public interface NewL2DSOConfig extends NewConfig {

  public static final String OBJECTDB_DIRNAME                      = "objectdb";
  public static final String DIRTY_OBJECTDB_BACKUP_DIRNAME         = "dirty-objectdb-backup";
  public static final String DIRTY_OBJECTDB_BACKUP_PREFIX          = "dirty-objectdb-";
  public static final short  DEFAULT_GROUPPORT_OFFSET_FROM_DSOPORT = 20;

  PersistenceMode persistenceMode();

  boolean garbageCollectionEnabled();

  boolean garbageCollectionVerbose();

  int garbageCollectionInterval();

  BindPort dsoPort();

  BindPort l2GroupPort();

  String host();

  String serverName();

  int clientReconnectWindow();

  String bind();

  Offheap offHeapConfig();
  
  //STRICTLY fot test
  
  void setPersistenceMode(PersistenceMode persistenceMode);
  
  void setGrabgeCollectionEnabled(boolean garbageCollection);
  
  void setGarbageCollectionVerbose(boolean garbageCollectionVerbose);
  
  void setGarbageCollectionInterval(int garbageCollectionInterval);
  
  void setDsoPort(BindPort dsoPort);
  
  void setL2GroupPort(BindPort l2GroupPort);
  
  void setClientReconnectWindo(int clinetReconnectWindow);
  
  void setOffHeap(Offheap offheap);

  void setBind(String bind);

}

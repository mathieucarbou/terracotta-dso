/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.groups;

import com.tc.net.protocol.tcm.MessageChannel;

public interface TCGroupMember {

  public NodeIdComparable getSrcNodeID();

  public NodeIdComparable getDstNodeID();

  public NodeIdComparable getPeerNodeID();

  public MessageChannel getChannel();

  public void send(GroupMessage msg) throws GroupException;

  public void setTCGroupManager(TCGroupManagerImpl manager);

  public TCGroupManagerImpl getTCGroupManager();

  public boolean isReady();
  
  public void setReady(boolean isReady);

  public void close();

  public boolean highPriorityLink();
}
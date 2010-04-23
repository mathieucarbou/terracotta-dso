/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.config;

import com.tc.config.schema.ActiveServerGroupConfig;
import com.tc.config.schema.ActiveServerGroupsConfig;
import com.tc.config.schema.setup.ConfigurationSetupException;
import com.tc.config.schema.setup.L2TVSConfigurationSetupManager;
import com.tc.net.GroupID;
import com.tc.net.OrderedGroupIDs;
import com.tc.net.TCSocketAddress;
import com.tc.net.groups.Node;
import com.tc.net.groups.ServerGroup;
import com.tc.object.config.schema.NewL2DSOConfig;
import com.tc.util.Assert;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class HaConfigImpl implements HaConfig {

  private final L2TVSConfigurationSetupManager configSetupManager;
  private final GroupID[]                      groupIDs;
  private final GroupID                        thisGroupID;

  private final NodesStoreImpl                     nodeStore;
  private final ServerGroup[]                  groups;
  private final ServerGroup                    activeCoordinatorGroup;
  private final Node                           thisNode;
  private final ServerGroup                    thisGroup;
  private final ServerNameToGroupIDMappingImpl serverNameToGid;

  public HaConfigImpl(L2TVSConfigurationSetupManager configSetupManager) {
    this.configSetupManager = configSetupManager;
    ActiveServerGroupsConfig groupsConfig = this.configSetupManager.activeServerGroupsConfig();
    int groupCount = groupsConfig.getActiveServerGroupCount();
    this.groups = new ServerGroup[groupCount];
    this.groupIDs = new GroupID[groupCount];
    for (int i = 0; i < groupCount; i++) {
      this.groups[i] = new ServerGroup(groupsConfig.getActiveServerGroupArray()[i]);
      this.groupIDs[i] = groups[i].getGroupId();
    }

    GroupID activeCoordinatorGroupId = new OrderedGroupIDs(groupIDs).getActiveCoordinatorGroup();
    ServerGroup tempServerGrp = null;
    // Search for the group id which matches activeCoordinator GroupId
    for (int i = 0; i < groupCount; i++) {
      if (activeCoordinatorGroupId.equals(groups[i].getGroupId())) {
        tempServerGrp = groups[i];
        break;
      }
    }

    this.activeCoordinatorGroup = tempServerGrp;
    Assert.assertNotNull(this.activeCoordinatorGroup);

    Set<Node> nodes = makeAllNodes();
    this.thisNode = makeThisNode();
    this.thisGroup = getThisGroupFrom(this.groups, this.configSetupManager.getActiveServerGroupForThisL2());
    this.thisGroupID = this.thisGroup.getGroupId();

    this.nodeStore = new NodesStoreImpl(nodes, getNodeNamesForThisGroup());
    this.serverNameToGid = new ServerNameToGroupIDMappingImpl(buildServerGroupIDMap());
  }

  private HashMap<String, GroupID> buildServerGroupIDMap() {
    HashMap<String, GroupID> tempMap = new HashMap<String, GroupID>();
    for (ServerGroup group : groups) {
      for (Node node : group.getNodes()) {
        tempMap.put(node.getServerNodeName(), group.getGroupId());
      }
    }
    return tempMap;
  }

  private Set<String> getNodeNamesForThisGroup() {
    Set<String> tmpSet = new HashSet<String>();
    for (Node n : thisGroup.getNodes()) {
      tmpSet.add(n.getServerNodeName());
    }
    return tmpSet;
  }

  /**
   * @throws ConfigurationSetupException
   */
  public ReloadConfigChangeContext reloadConfiguration() throws ConfigurationSetupException {
    ActiveServerGroupsConfig asgsc = this.configSetupManager.activeServerGroupsConfig();
    int grpCount = asgsc.getActiveServerGroupCount();

    ReloadConfigChangeContext context = new ReloadConfigChangeContext();

    ActiveServerGroupConfig[] asgcArray = asgsc.getActiveServerGroupArray();
    for (int i = 0; i < grpCount; i++) {
      GroupID gid = asgcArray[i].getGroupId();
      for (int j = 0; j < grpCount; j++) {
        if (groups[i].getGroupId().equals(gid)) {
          ReloadConfigChangeContext tempContext = groups[i].reloadGroup(this.configSetupManager, asgcArray[i]);
          context.update(tempContext);

          serverNameToGid.updateServerNames(tempContext, gid);

          if (groups[i] == this.thisGroup) {
            updateNamesForThisGroup(tempContext);
          }
        }
      }
    }

    nodeStore.updateNodes(context);
    return context;
  }

  private void updateNamesForThisGroup(ReloadConfigChangeContext context) {
    nodeStore.updateServerNames(context);
  }

  private ServerGroup getThisGroupFrom(ServerGroup[] sg, ActiveServerGroupConfig activeServerGroupForThisL2) {
    for (int i = 0; i < sg.length; i++) {
      if (sg[i].getGroupId() == activeServerGroupForThisL2.getGroupId()) { return sg[i]; }
    }
    throw new RuntimeException("Unable to find this group information for " + this.thisNode + " "
                               + activeServerGroupForThisL2);
  }

  public boolean isActiveActive() {
    return this.configSetupManager.activeServerGroupsConfig().getActiveServerGroupCount() > 1;
  }

  public boolean isDiskedBasedActivePassive() {
    return !isActiveActive() && !isNetworkedActivePassive();
  }

  public boolean isNetworkedActivePassive() {
    return this.configSetupManager.haConfig().isNetworkedActivePassive();
  }

  public GroupID getActiveCoordinatorGroupID() {
    return this.activeCoordinatorGroup.getGroupId();
  }

  public GroupID getThisGroupID() {
    return this.thisGroupID;
  }

  public GroupID[] getGroupIDs() {
    return this.groupIDs;
  }

  private Set<Node> makeAllNodes() {
    Set allClusterNodes = new HashSet();
    ActiveServerGroupConfig[] asgcs = this.configSetupManager.activeServerGroupsConfig().getActiveServerGroupArray();
    for (int j = 0; j < asgcs.length; ++j) {
      ActiveServerGroupConfig asgc = asgcs[j];
      Assert.assertNotNull(asgc);
      String[] l2Names = asgc.getMembers().getMemberArray();
      for (int i = 0; i < l2Names.length; i++) {
        try {
          NewL2DSOConfig l2 = this.configSetupManager.dsoL2ConfigFor(l2Names[i]);
          Node node = makeNode(l2);
          allClusterNodes.add(node);
          addNodeToGroup(node, l2Names[i]);
        } catch (ConfigurationSetupException e) {
          throw new RuntimeException("Error getting l2 config for: " + l2Names[i], e);
        }
      }
    }
    return allClusterNodes;
  }

  // servers and groups were checked in configSetupManger
  private void addNodeToGroup(Node node, String serverName) {
    boolean added = false;
    for (int i = 0; i < this.groups.length; i++) {
      if (this.groups[i].hasMember(serverName)) {
        this.groups[i].addNode(node, serverName);
        added = true;
      }
    }
    if (!added) { throw new AssertionError("Node=[" + node + "] with serverName=[" + serverName
                                           + "] was not added to any group!"); }
  }

  public Node getThisNode() {
    return this.thisNode;
  }

  private Node makeThisNode() {
    NewL2DSOConfig l2 = this.configSetupManager.dsoL2Config();
    return makeNode(l2);
  }

  public static Node makeNode(NewL2DSOConfig l2) {
    return new Node(l2.host().getString(), l2.listenPort().getInt(), l2.l2GroupPort().getInt(),
                    TCSocketAddress.WILDCARD_IP);
  }

  public boolean isActiveCoordinatorGroup() {
    return this.thisGroup == this.activeCoordinatorGroup;
  }

  public ServerNameToGroupIDMapping getServerNameToGroupID() {
    return serverNameToGid;
  }

  public ServerNamesOfThisGroup getServerNamesOfThisGroup() {
    return nodeStore;
  }

  public NodesStore getNodesStore() {
    return nodeStore;
  }
}

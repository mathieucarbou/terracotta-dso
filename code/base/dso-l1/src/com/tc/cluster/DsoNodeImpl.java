/*
 * All content copyright (c) 2003-2009 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.cluster;

import com.tc.util.Assert;

public class DsoNodeImpl implements DsoNode {

  private final String id;

  public DsoNodeImpl(final String id) {
    Assert.assertNotNull(id);
    this.id = id;
  }

  public String getId() {
    return id;
  }

  public String getIp() {
    throw new UnsupportedOperationException();
  }

  public String getHostname() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return id;
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) { return true; }
    if (null == obj) { return false; }
    if (getClass() != obj.getClass()) { return false; }
    DsoNodeImpl other = (DsoNodeImpl) obj;
    if (null == id) {
      return null == other.id;
    } else {
      return id.equals(other.id);
    }
  }
}

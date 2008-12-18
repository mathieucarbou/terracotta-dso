/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.util;

import com.tc.util.TCTimer;
import com.tc.util.TCTimerImpl;
import com.tc.util.msg.TickerTokenMessage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TimerTask;

public abstract class TickerTokenManager<T extends TickerToken, M extends TickerTokenMessage> {

  private final int                                id;
  private final int                                timerPeriod;
  private final Map<Integer, TCTimer>              timerMap          = Collections
                                                                         .synchronizedMap(new HashMap<Integer, TCTimer>());
  protected final TickerTokenFactory<T, M>              factory;
  private final Map<Class, TickerTokenHandler>           tallyTokenMap     = Collections
                                                                         .synchronizedMap(new HashMap<Class, TickerTokenHandler>());
  private final Map<Class, TickerTokenCompleteHandler> completeTickerMap = Collections
                                                                         .synchronizedMap(new HashMap<Class, TickerTokenCompleteHandler>());
  private final Counter                            tickValue         = new Counter();

  public TickerTokenManager(int id, int timerPeriod, TickerTokenFactory factory) {
    this.id = id;
    this.factory = factory;
    this.timerPeriod = timerPeriod;
  }

  public TickerTokenFactory getFactory() {
    return this.factory;
  }

  public int getId() {
    return id;
  }

  public void addTickerTokenHandler(Class tokenClass, TickerTokenHandler handler) {
    tallyTokenMap.put(tokenClass, handler);
  }

  public void addTickerTokenCompleteHandler(Class tokenClass, TickerTokenCompleteHandler listener) {
    completeTickerMap.put(tokenClass, listener);
  }

  public void startTicker() {
    TCTimer timer = new TCTimerImpl("Ticker Timer", false);
    TickerTask task = new TickerTask(this.tickValue, this, factory, timerMap, timer);
    timer.schedule(task, timerPeriod, timerPeriod);
  }

  public void send(T token) {
    TickerTokenHandler handler = tallyTokenMap.get(token.getClass());
    Assert.assertNotNull(handler);
    handler.processToken(token);
    M message = factory.createMessage(token);
    sendMessage(message);
  }

  public abstract void sendMessage(M message);

  public void recieve(T token) {
    int cid = token.getPrimaryID();
    if (cid == this.id) {
      boolean dirty = false;
      for (Iterator<Boolean> iter = token.getTokenStateMap().values().iterator(); iter.hasNext();) {
        if (iter.next().booleanValue()) {
          dirty = true;
        }
      }
      if (!dirty && evaluateComplete(token)) {
        complete(token);
        return;
      }
    }
    send(token);
  }

  public abstract boolean evaluateComplete(T token);

  private void complete(T token) {
    TCTimer t = timerMap.remove(token.getPrimaryTickValue());
    System.out.println("id: " + id + " Timer value: " + t + " tickValue: " + token.getPrimaryTickValue());
    if (t != null) {

      t.cancel();
    }
    completeTickerMap.get(token.getClass()).complete();

  }

  private static class TickerTask<T extends TickerToken, M extends TickerTokenMessage> extends TimerTask {

    private final TickerTokenManager<T, M> manager;
    private final TickerTokenFactory<T, M> factory;
    private final Map           timerMap;
    private final TCTimer       timer;
    private final Counter       tickValue;

    private TickerTask(Counter tickValue, TickerTokenManager manager, TickerTokenFactory factory, Map timerMap, TCTimer timer) {
      this.tickValue = tickValue;
      this.manager = manager;
      this.factory = factory;
      this.timer = timer;
      this.timerMap = timerMap;
    }

    public void run() {
      T token = factory.createTriggerToken(manager.getId(), tickValue.increment());
      System.out.println("Put into timer map: tickValue: " + token.getPrimaryTickValue() + " timer: " + timer);
      timerMap.put(token.getPrimaryTickValue(), timer);
      manager.send(token);
    }

  }
}

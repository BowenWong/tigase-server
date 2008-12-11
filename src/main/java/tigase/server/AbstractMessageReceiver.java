/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2007 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.server;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.regex.Pattern;
import tigase.conf.Configurable;
import tigase.stats.StatRecord;
import tigase.stats.StatisticType;
import tigase.stats.StatisticsContainer;
import tigase.util.JIDUtils;
import tigase.util.DNSResolver;
import tigase.vhosts.VHostListener;
import tigase.vhosts.VHostManagerIfc;

/**
 * Describe class AbstractMessageReceiver here.
 *
 *
 * Created: Tue Nov 22 07:07:11 2005
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public abstract class AbstractMessageReceiver
  implements StatisticsContainer, MessageReceiver, Configurable, VHostListener {

	protected static final long SECOND = 1000;
	protected static final long MINUTE = 60*SECOND;
	protected static final long HOUR = 60*MINUTE;

	private String DEF_HOSTNAME_PROP_VAL = DNSResolver.getDefaultHostname();
	public static final String MAX_QUEUE_SIZE_PROP_KEY = "max-queue-size";
	//  public static final Integer MAX_QUEUE_SIZE_PROP_VAL = Integer.MAX_VALUE;
  public static final Integer MAX_QUEUE_SIZE_PROP_VAL =
    new Long(Runtime.getRuntime().maxMemory()/100000L).intValue();

  /**
   * Variable <code>log</code> is a class logger.
   */
  private static final Logger log =
    Logger.getLogger("tigase.abstract.AbstractMessageReceiver");

  protected int maxQueueSize = MAX_QUEUE_SIZE_PROP_VAL;
	private String defHostname = DEF_HOSTNAME_PROP_VAL;

  private MessageReceiver parent = null;
	//	private Timer delayedTask = new Timer("MessageReceiverTask", true);

  private LinkedBlockingQueue<Packet> in_queue =
		new LinkedBlockingQueue<Packet>(maxQueueSize);
  private LinkedBlockingQueue<Packet> out_queue =
		new LinkedBlockingQueue<Packet>(maxQueueSize);

	// 	private String sync = "SyncObject";

	private Timer receiverTasks = null;
	private Thread in_thread = null;
	private Thread out_thread = null;
  private boolean stopped = false;
  private String name = null;
	protected VHostManagerIfc vHostManager = null;
	//private Set<String> routings = new CopyOnWriteArraySet<String>();
	private Set<Pattern> regexRoutings = new CopyOnWriteArraySet<Pattern>();
	private long curr_second = 0;
	private long curr_minute = 0;
	private long curr_hour = 0;
	private long[] seconds = new long[60];
	private int sec_idx = 0;
	private long[] minutes = new long[60];
	private int min_idx = 0;
	private String compId = null;
	private long[] processPacketTimings = new long[100];
	private int pptIdx = 0;

  /**
   * Variable <code>statAddedMessagesOk</code> keeps counter of successfuly
   * added messages to queue.
   */
  private long statReceivedMessagesOk = 0;
  private long statSentMessagesOk = 0;
  /**
   * Variable <code>statAddedMessagesEr</code> keeps counter of unsuccessfuly
   * added messages due to queue overflow.
   */
  private long statReceivedMessagesEr = 0;
  private long statSentMessagesEr = 0;

	/**
	 * Describe <code>getComponentId</code> method here.
	 *
	 * @return a <code>String</code> value
	 */
	public String getComponentId() {
		return compId;
	}
	
	@Override
	public void initializationCompleted() {}

  public boolean addPacketNB(Packet packet) {
		if (log.isLoggable(Level.FINEST)) {
			log.finest(">" + getName() + "<  " + packet.toString());
		}
		boolean result = in_queue.offer(packet);
		if (result) {
			++statReceivedMessagesOk;
			++curr_second;
		} else {
			++statReceivedMessagesEr;
// 			log.warning("Can't add more packets to the queue: "
// 				+ "size=" + in_queue.size()
// 				+ ", remaining=" + in_queue.remainingCapacity());
		}
		return result;
  }

  public boolean addPackets(Queue<Packet> packets) {
		Packet p = null;
		boolean result = true;
		while ((p = packets.peek()) != null) {
			result = addPacket(p);
			if (result) {
				packets.poll();
			} else {
				return false;
			} // end of if (result) else
		} // end of while ()
    return true;
  }

	public boolean addPacket(Packet packet) {
		if (log.isLoggable(Level.FINEST)) {
			log.finest(">" + getName() + "<  " + packet.toString());
		}
		try {
			in_queue.put(packet);
			++statReceivedMessagesOk;
			++curr_second;
		} catch (InterruptedException e) {
			++statReceivedMessagesEr;
			return false;
		} // end of try-catch
		return true;
  }

	/**
	 * Non blocking version of <code>addOutPacket</code>.
	 *
	 * @param packet a <code>Packet</code> value
	 * @return a <code>boolean</code> value
	 */
	protected boolean addOutPacketNB(Packet packet) {
		if (log.isLoggable(Level.FINEST)) {
			log.finest(">" + getName() + "<  " + packet.toString());
		}
		boolean result = out_queue.offer(packet);
		if (result) {
			++statSentMessagesOk;
			//++curr_second;
		} else {
			++statSentMessagesEr;
		}
		return result;
	}

	protected boolean addOutPacket(Packet packet) {
		if (log.isLoggable(Level.FINEST)) {
			log.finest(">" + getName() + "<  " + packet.toString());
		}
		try {
			out_queue.put(packet);
			++statSentMessagesOk;
			//++curr_second;
		} catch (InterruptedException e) {
			++statSentMessagesEr;
			return false;
		} // end of try-catch
		return true;
	}

	protected boolean addOutPackets(Queue<Packet> packets) {
		Packet p = null;
		boolean result = true;
		while ((p = packets.peek()) != null) {
			result = addOutPacket(p);
			if (result) {
				packets.poll();
			} else {
				return false;
			} // end of if (result) else
		} // end of while ()
    return true;
	}

  public abstract void processPacket(Packet packet);

  public List<StatRecord> getStatistics() {
    List<StatRecord> stats = new LinkedList<StatRecord>();
		stats.add(new StatRecord(getName(), "Last second packets", "int",
				seconds[(sec_idx == 0 ? 59 : sec_idx - 1)], Level.FINE));
		stats.add(new StatRecord(getName(), "Last minute packets", "int",
				minutes[(min_idx == 0 ? 59 : min_idx - 1)], Level.FINE));
		// 		long curr_hour = 0;
		// 		for (long min: minutes) { curr_hour += min; }
		//for (int sec: seconds) { curr_hour += sec; }
		//curr_hour += curr_second;
		// 		if (curr_hour > statAddedMessagesOk) {
		// 			// This is not a dirty hack!! It looks weird but this is correct.
		// 			// Last second, minute and hour are calculated in different threads
		// 			// from the main packets processing thread and sometimes might
		// 			// be out of sync. It does look odd on stats page so this statements
		// 			// saves from long explanations to non-techies.
		// 			curr_hour = statAddedMessagesOk;
		// 		}
		stats.add(new StatRecord(getName(), "Last hour packets", "int",
				curr_hour, Level.FINE));
    stats.add(new StatRecord(getName(), StatisticType.MSG_RECEIVED_OK,
				statReceivedMessagesOk, Level.FINE));
    stats.add(new StatRecord(getName(), StatisticType.MSG_SENT_OK,
				statSentMessagesOk, Level.FINE));
    stats.add(new StatRecord(getName(), StatisticType.QUEUE_WAITING,
				(in_queue.size() + out_queue.size()), Level.FINEST));
    stats.add(new StatRecord(getName(), StatisticType.MAX_QUEUE_SIZE,
				maxQueueSize, Level.FINEST));
    stats.add(new StatRecord(getName(), StatisticType.IN_QUEUE_OVERFLOW,
				statReceivedMessagesEr, Level.FINEST));
    stats.add(new StatRecord(getName(), StatisticType.OUT_QUEUE_OVERFLOW,
				statSentMessagesEr, Level.FINEST));
		long res = 0;
		for (long ppt : processPacketTimings) {
			res += ppt;
		}
		stats.add(new StatRecord(getName(),
						"Average processing time on last " +
						processPacketTimings.length + " runs [ms]", "long",
				(res/processPacketTimings.length), Level.FINEST));
    return stats;
  }

  /**
   * Sets all configuration properties for object.
   */
  public void setProperties(Map<String, Object> props) {
    int queueSize = (Integer)props.get(MAX_QUEUE_SIZE_PROP_KEY);
    setMaxQueueSize(queueSize);
		defHostname = (String)props.get(DEF_HOSTNAME_PROP_KEY);
		compId = (String)props.get(COMPONENT_ID_PROP_KEY);
		//addRouting(getComponentId());
  }

  public void setMaxQueueSize(int maxQueueSize) {
    if (this.maxQueueSize != maxQueueSize) {
			stopThreads();
      this.maxQueueSize = maxQueueSize;
      if (in_queue != null) {
				LinkedBlockingQueue<Packet> newQueue =
					new LinkedBlockingQueue<Packet>(maxQueueSize);
				newQueue.addAll(in_queue);
				in_queue = newQueue;
      } // end of if (queue != null)
      if (out_queue != null) {
				LinkedBlockingQueue<Packet> newQueue =
					new LinkedBlockingQueue<Packet>(maxQueueSize);
				newQueue.addAll(out_queue);
				out_queue = newQueue;
      } // end of if (queue != null)
			startThreads();
    } // end of if (this.maxQueueSize != maxQueueSize)
  }

	//   public void setLocalAddresses(String[] addresses) {
	//     localAddresses = addresses;
	//   }

	protected Integer getMaxQueueSize(int def) {
		return def;
	}

  /**
   * Returns defualt configuration settings for this object.
   */
  public Map<String, Object> getDefaults(Map<String, Object> params) {
    Map<String, Object> defs = new LinkedHashMap<String, Object>();
		//maxQueueSize = MAX_QUEUE_SIZE_PROP_VAL;
		String queueSize = (String)params.get(GEN_MAX_QUEUE_SIZE);
		int queueSizeInt = MAX_QUEUE_SIZE_PROP_VAL;
		if (queueSize != null) {
			try {
				queueSizeInt = Integer.parseInt(queueSize);
			} catch (NumberFormatException e) {
				queueSizeInt = MAX_QUEUE_SIZE_PROP_VAL;
			}
		}
		defs.put(MAX_QUEUE_SIZE_PROP_KEY, getMaxQueueSize(queueSizeInt));
// 		if (params.get(GEN_VIRT_HOSTS) != null) {
// 			DEF_HOSTNAME_PROP_VAL = ((String)params.get(GEN_VIRT_HOSTS)).split(",")[0];
// 		} else {
		// The default hostname must be a real name of the machine and is a separate
		// thing from virtual hostnames. This is a critical parameter for proper
		// MessageRouter working.
		DEF_HOSTNAME_PROP_VAL = DNSResolver.getDefaultHostname();
// 		}
		defs.put(DEF_HOSTNAME_PROP_KEY, DEF_HOSTNAME_PROP_VAL);
		defs.put(COMPONENT_ID_PROP_KEY, compId);

    return defs;
  }

  public void release() {
    stop();
  }

  public void setParent(MessageReceiver parent) {
    this.parent = parent;
		//addRouting(getDefHostName());
  }

  public void setName(String name) {
    this.name = name;
		compId = JIDUtils.getNodeID(name, defHostname);
  }

  public String getName() {
    return name;
  }

	private void stopThreads() {
    stopped = true;
		try {
			if (in_thread != null) {
				in_thread.interrupt();
				while (in_thread.isAlive()) {
					Thread.sleep(100);
				}
			}
			if (out_thread != null) {
				out_thread.interrupt();
				while (out_thread.isAlive()) {
					Thread.sleep(100);
				}
			}
		} catch (InterruptedException e) {}
		in_thread = null;
		out_thread = null;
		if (receiverTasks != null) {
			receiverTasks.cancel();
			receiverTasks = null;
		}
	}

	public synchronized void everySecond() {
		curr_minute -= seconds[sec_idx];
		seconds[sec_idx] = curr_second;
		curr_second = 0;
		curr_minute += seconds[sec_idx];
		if (sec_idx >= 59) {
			sec_idx = 0;
		} else {
			++sec_idx;
		}
	}

	public synchronized void everyMinute() {
		curr_hour -= minutes[min_idx];
		minutes[min_idx] = curr_minute;
		curr_hour += minutes[min_idx];
		if (min_idx >= 59) {
			min_idx = 0;
		} else {
			++min_idx;
		}
	}

	private void startThreads() {
		if (in_thread == null || ! in_thread.isAlive()) {
			stopped = false;
			in_thread = new Thread(new QueueListener(in_queue, QueueType.IN_QUEUE));
			in_thread.setName("in_" + name);
			in_thread.start();
		} // end of if (thread == null || ! thread.isAlive())
		if (out_thread == null || ! out_thread.isAlive()) {
			stopped = false;
			out_thread = new Thread(new QueueListener(out_queue, QueueType.OUT_QUEUE));
			out_thread.setName("out_" + name);
			out_thread.start();
		} // end of if (thread == null || ! thread.isAlive())
		receiverTasks = new Timer(getName() + " tasks", true);
		receiverTasks.schedule(new TimerTask() {
				public void run() {
					everySecond();
				}
			}, SECOND, SECOND);
		receiverTasks.schedule(new TimerTask() {
				public void run() {
					everyMinute();
				}
			}, MINUTE, MINUTE);
	}

	public void start() {
		log.finer(getName() + ": starting queue management threads ...");
		startThreads();
  }

  public void stop() {
		log.finer(getName() + ": stopping queue management threads ...");
		stopThreads();
  }

	public String getDefHostName() {
		return defHostname;
	}

	public boolean handlesLocalDomains() {
		return false;
	}

	public boolean handlesNameSubdomains() {
		return true;
	}

	public boolean handlesNonLocalDomains() {
		return false;
	}

	public void setVHostManager(VHostManagerIfc manager) {
		this.vHostManager = manager;
	}

	public boolean isLocalDomain(String domain) {
		return vHostManager != null ? vHostManager.isLocalDomain(domain) : false;
	}

	public boolean isLocalDomainOrComponent(String domain) {
		return vHostManager != null ? vHostManager.isLocalDomainOrComponent(domain)
						: false;
	}

//	public Set<String> getRoutings() {
//		return routings;
//	}

//	public void addRouting(String address) {
//		routings.add(address);
//		log.fine(getName() + " - added routing: " + address);
//	}

//	public boolean removeRouting(String address) {
//		return routings.remove(address);
//	}

//	public void clearRoutings() {
//		routings.clear();
//	}

//	public boolean isInRoutings(String host) {
//		return routings.contains(host);
//	}

	public Set<Pattern> getRegexRoutings() {
		return regexRoutings;
	}

	public void addRegexRouting(String address) {
		log.fine(getName() + " - attempt to add regex routing: " + address);
		regexRoutings.add(Pattern.compile(address, Pattern.CASE_INSENSITIVE));
		log.fine(getName() + " - success adding regex routing: " + address);
	}

	public boolean removeRegexRouting(String address) {
		return regexRoutings.remove(Pattern.compile(address,
				Pattern.CASE_INSENSITIVE));
	}

	public void clearRegexRoutings() {
		regexRoutings.clear();
	}

	public boolean isInRegexRoutings(String address) {
		// 		log.finest(getName() + " looking for regex routings: " + address);
		for (Pattern pat: regexRoutings) {
			if (pat.matcher(address).matches()) {
				log.finest(getName() + " matched against pattern: " + pat.toString());
				return true;
			}
			// 			log.finest(getName() + " matching failed against pattern: " + pat.toString());
		}
		return false;
	}

	public final void processPacket(final Packet packet,
		final Queue<Packet> results)	{
		addPacketNB(packet);
	}

	private enum QueueType { IN_QUEUE, OUT_QUEUE }

	private class QueueListener implements Runnable {

		private LinkedBlockingQueue<Packet> queue = null;
		private QueueType type = null;

		private QueueListener(LinkedBlockingQueue<Packet> q, QueueType type) {
			this.queue = q;
			this.type = type;
		}

		public void run() {
			Packet packet = null;
			while (! stopped) {
				try {
					packet = queue.take();
					switch (type) {
					case IN_QUEUE:
						long startPPT = System.currentTimeMillis();
						processPacket(packet);
						processPacketTimings[pptIdx] =
										System.currentTimeMillis() - startPPT;
						if (pptIdx >= (processPacketTimings.length-1)) {
							pptIdx = 0;
						} else {
							++pptIdx;
						}
						break;
					case OUT_QUEUE:
						if (parent != null) {
							// 							log.finest(">" + getName() + "<  " +
							// 								"Sending outQueue to parent: " + parent.getName());
							parent.addPacket(packet);
						} else {
							// It may happen for MessageRouter and this is intentional
							addPacketNB(packet);
							//log.warning(">" + getName() + "<  " + "No parent!");
						} // end of else
						break;
					default:
						log.severe("Unknown queue element type: " + type);
						break;
					} // end of switch (qel.type)
				} catch (InterruptedException e) {
					//log.log(Level.SEVERE, "Exception during packet processing: ", e);
					//				stopped = true;
				} catch (Exception e) {
					log.log(Level.SEVERE, "[" + getName() +
									"] Exception during packet processing: " +
									packet.toString(), e);
				} // end of try-catch
			} // end of while (! stopped)
		}

	}

} // AbstractMessageReceiver

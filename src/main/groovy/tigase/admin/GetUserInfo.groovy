/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2012 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev: $
 * Last modified by $Author: $
 * $Date: $
 */

/*
 Obtaining informations about user
 AS:Description: Get User Info
 AS:CommandId: get-user-info
 AS:Component: sess-man
 AS:Group: Users
 */

package tigase.admin

import tigase.server.*
import tigase.util.*
import tigase.xml.*
import tigase.xmpp.*
import tigase.db.*
import tigase.xml.*
import tigase.vhosts.*
import tigase.cluster.*;
import tigase.cluster.api.*;
import tigase.cluster.strategy.*;


def JID = "accountjid"

def p = (Packet)packet
def sessions = (Map<BareJID, XMPPSession>)userSessions
def vhost_man = (VHostManagerIfc)vhostMan
def admins = (Set)adminsSet
def stanzaFromBare = p.getStanzaFrom().getBareJID()
def isServiceAdmin = admins.contains(stanzaFromBare)

def userJid = Command.getFieldValue(packet, JID)

if (userJid == null) {
	def result = p.commandResult(Command.DataType.form);

	Command.addTitle(result, "Get User Info")
	Command.addInstructions(result, "Fill out this form to gather informations about user.")

	Command.addFieldValue(result, "FORM_TYPE", "http://jabber.org/protocol/admin", "hidden")
	Command.addFieldValue(result, JID, userJid ?: "", "jid-single","The Jabber ID for statistics")
	Command.addCheckBoxField(result, "Show connected resources in table", true)

	return result
}

bareJID = BareJID.bareJIDInstance(userJid)
VHostItem vhost = vhost_man.getVHostItem(bareJID.getDomain())
def resourcesAsTable = Command.getCheckBoxFieldValue(p, "Show connected resources in table");
def result = p.commandResult(Command.DataType.result)

if (isServiceAdmin ||
(vhost != null && (vhost.isOwner(stanzaFromBare.toString()) || vhost.isAdmin(stanzaFromBare.toString())))) {

		Command.addTextField(result, "JID", "JID: " + userJid)
		boolean handled = false;
		if (this.hasProperty("clusterStrategy")) {
            def cluster = (ClusteringStrategyIfc) clusterStrategy
			def conns = cluster.getConnectionRecords(bareJID);
			if (cluster.containsJid(bareJID) && (conns != null)) {
				handled = true;
				def recs = [];
				recs.addAll(conns).sort { it.getUserJid().getResource() }
				Command.addTextField(result, "Status", "Status: " + (conns.size() ? "online" : "offline"))
				Command.addTextField(result, "Active connections", "Active connections: " + conns.size())
				if (resourcesAsTable) {
					Element reported = new Element("reported");
					reported.addAttribute("label", "Connected resources");
					def cols = ["Resource", "Cluster node"];
					cols.each {
						Element el = new Element("field");
						el.setAttribute("var", it);
						reported.addChild(el);
					}
					result.getElement().getChild('command').getChild('x').addChild(reported);
					recs.each { rec ->
						Element item = new Element("item");
						Element res = new Element("field");
						res.setAttribute("var", "Resource");
						res.addChild(new Element("value", rec.getUserJid().getResource()));
						item.addChild(res);
					
						Element node = new Element("field");
						node.setAttribute("var", "Cluster node");
						node.addChild(new Element("value", con.getNode().toString()));
						item.addChild(node);
						result.getElement().getChild('command').getChild('x').addChild(item);
					}
				} else {
					recs.each { rec ->
						Command.addTextField(result, rec.getUserJid().getResource() + " is connected to", rec.getUserJid().getResource() + " is connected to " + con.getNode().toString());
					}
				}
			}
		} 
		if (!handled) {
			XMPPSession session = sessions.get(BareJID.bareJIDInstanceNS(userJid))
			if (session != null) {
				List<XMPPResourceConnection> conns = session.getActiveResources()
				Command.addTextField(result, "Status", "Status: " + (conns.size() ? "online" : "offline"))
				Command.addTextField(result, "Active connections", "Active connections: " + conns.size())
				if (resourcesAsTable) {
					Element reported = new Element("reported");
					reported.addAttribute("label", "Connected resources");
					def cols = ["Resource", "Cluster node"];
					cols.each {
						Element el = new Element("field");
						el.setAttribute("var", it);
						reported.addChild(el);
					}
					result.getElement().getChild('command').getChild('x').addChild(reported);
					conns.each { con ->	
						Element item = new Element("item");
						Element res = new Element("field");
						res.setAttribute("var", "Resource");
						res.addChild(new Element("value", con.getResource()));
						item.addChild(res);
					
						Element node = new Element("field");
						node.setAttribute("var", "Cluster node");
						node.addChild(new Element("value", con.getConnectionId()?.getDomain()));
						item.addChild(node);
						result.getElement().getChild('command').getChild('x').addChild(item);
					}				
				} else {
					for (XMPPResourceConnection con: conns) {
						Command.addTextField(result, con.getResource() + " is connected to", con.getResource() + " is connected to " + con.getResource()?.getDomain());
					}
				}
			} else {
				Command.addTextField(result, "Status", "Status: offline")
			}
		}
		def sessionManager = component;
		def offlineMsgsRepo = sessionManager.processors.values().find { it.hasProperty("msg_repo") }?.msg_repo;
		if (offlineMsgsRepo && offlineMsgsRepo.metaClass.respondsTo(offlineMsgsRepo, "getMessagesCount", [tigase.xmpp.JID] as Object[])) {
			def offlineStats = offlineMsgsRepo.getMessagesCount(tigase.xmpp.JID.jidInstance(bareJID));
			def msg = "Offline messages: " + (offlineStats ? (offlineStats[offlineStats.keySet().find { it.name() == "message" }] ?: 0) : 0);
			Command.addTextField(result, msg, msg);
		}		
		
		def loginHistoryProcessor = sessionManager.outFilters["login-history"];
		if (loginHistoryProcessor) {
			def unifiedArchiveComp = tigase.server.XMPPServer.getComponent(loginHistoryProcessor.getComponentJid().getLocalpart())//sessionManager.parent.components_byId[loginHistoryProcessor.componentJid];
			if (unifiedArchiveComp) {
				def ua_repo = unifiedArchiveComp.msg_repo;
				def criteria = ua_repo.newCriteriaInstance();
				criteria.setWith(bareJID.toString());
				criteria.getRSM().hasBefore = true;
				criteria.getRSM().max = 10;
				criteria.itemType = "login";
//				def logins = ua_repo.getItems(bareJID, criteria).reverse().collect { new java.util.Date(criteria.getStart().getTime() + 
//							(Integer.parseInt(it.getAttribute("secs"))*1000)).toString() + " for resource '" + it.getChildren().first().getCData() + "'" }.join("\n");

				Element reported = new Element("reported");
				reported.addAttribute("label", "Login times");
				def cols = ["Resource", "Date"];
				cols.each {
					Element el = new Element("field");
					el.setAttribute("var", it);
					reported.addChild(el);
				}
				result.getElement().getChild('command').getChild('x').addChild(reported);
			
				ua_repo.getItems(bareJID, criteria).reverse().each {
					Element item = new Element("item");
					Element res = new Element("field");
					res.setAttribute("var", "Resource");
					res.addChild(new Element("value", it.getChildren().first().getCData()));
					item.addChild(res);
					
					String ts = new java.util.Date(criteria.getStart().getTime() + 
						(Integer.parseInt(it.getAttribute("secs"))*1000)).format("yyyy-MM-dd HH:mm:ss.S");
					Element node = new Element("field");
					node.setAttribute("var", "Date");
					node.addChild(new Element("value", ts));
					item.addChild(node);
					result.getElement().getChild('command').getChild('x').addChild(item);
				}
			}
		}
} else {
	Command.addTextField(result, "Error", "You do not have enough permissions to obtain statistics for user in this domain.");
}

return result
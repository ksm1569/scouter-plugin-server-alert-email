/*
 *  Copyright 2016 Scouter Project.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); 
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. 
 *  
 *  @author Sang-Cheon Park
 */
package scouter.plugin.server.alert.email;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.mail.DefaultAuthenticator;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;

import scouter.lang.AlertLevel;
import scouter.lang.TextTypes;
import scouter.lang.TimeTypeEnum;
import scouter.lang.counters.CounterConstants;
import scouter.lang.pack.AlertPack;
import scouter.lang.pack.MapPack;
import scouter.lang.pack.ObjectPack;
import scouter.lang.pack.PerfCounterPack;
import scouter.lang.pack.XLogPack;
import scouter.lang.plugin.PluginConstants;
import scouter.lang.plugin.annotation.ServerPlugin;
import scouter.net.RequestCmd;
import scouter.server.Configure;
import scouter.server.CounterManager;
import scouter.server.Logger;
import scouter.server.core.AgentManager;
import scouter.server.db.TextRD;
import scouter.server.netio.AgentCall;
import scouter.util.DateUtil;
import scouter.util.HashUtil;

/**
 * Scouter server plugin to send alert via email
 * 
 * @author Sang-Cheon Park(nices96@gmail.com) on 2016. 3. 28.
 */
public class EmailPlugin {

    // Get singleton Configure instance from server
    final Configure conf = Configure.getInstance();

    private static AtomicInteger ai = new AtomicInteger(0);
    private static List<Integer> javaeeObjHashList = new ArrayList<Integer>();
    private static AlertPack lastPack;
    private static long lastSentTimestamp;

    public EmailPlugin() {
        if (ai.incrementAndGet() == 1) {
            ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

            // thread count check
            executor.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    for (int objHash : javaeeObjHashList) {
                        try {
                            if (AgentManager.isActive(objHash)) {
                                ObjectPack objectPack = AgentManager.getAgent(objHash);
                                MapPack mapPack = new MapPack();
                                mapPack.put("objHash", objHash);

                                mapPack = AgentCall.call(objectPack, RequestCmd.OBJECT_THREAD_LIST, mapPack);

                                int threadCountThreshold = conf.getInt("ext_plugin_thread_count_threshold", 0);
                                int threadCount = mapPack.getList("name").size();

                                if (threadCountThreshold != 0 && threadCount > threadCountThreshold) {
                                    AlertPack ap = new AlertPack();

                                    ap.level = AlertLevel.WARN;
                                    ap.objHash = objHash;
                                    ap.title = "스레드 수(" + threadCount + ")가 임계값(" + threadCountThreshold + ")을 초과했습니다.";
                                    ap.message = objectPack.objName + "의 스레드 수(" + threadCount + ")가 임계값(" + threadCountThreshold + ")을 초과했습니다.";
                                    ap.time = System.currentTimeMillis();
                                    ap.objType = objectPack.objType;

                                    alert(ap);
                                }
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
            }, 0, 5, TimeUnit.SECONDS);
        }
    }

    @ServerPlugin(PluginConstants.PLUGIN_SERVER_ALERT)
    public void alert(final AlertPack pack) {
        if (conf.getBoolean("ext_plugin_email_send_alert", false)) {

            // Get log level (0 : INFO, 1 : WARN, 2 : ERROR, 3 : FATAL)
            int level = conf.getInt("ext_plugin_email_level", 0);

            if (level <= pack.level) {
                new Thread() {
                    public void run() {
                        try {
                            // Get server configurations for email
                            String hostname = conf.getValue("ext_plugin_email_smtp_hostname", "smtp.gmail.com");
                            int port = conf.getInt("ext_plugin_email_smtp_port", 587);
                            boolean authEnabled = conf.getBoolean("ext_plugin_email_smtpauth_enabled", true);
                            String username = conf.getValue("ext_plugin_email_username");
                            String password = conf.getValue("ext_plugin_email_password");
                            boolean sslEnabled = conf.getBoolean("ext_plugin_email_ssl_enabled", true);
                            boolean tlsEnabled = conf.getBoolean("ext_plugin_email_starttls_enabled", true);
                            String from = conf.getValue("ext_plugin_email_from_address");
                            String to = conf.getValue("ext_plugin_email_to_address");
                            String cc = conf.getValue("ext_plugin_email_cc_address");

                            assert hostname != null;
                            assert port > 0;
                            assert username != null;
                            assert password != null;
                            assert from != null;
                            assert to != null;

                            // Get agent Name
                            String name = AgentManager.getAgentName(pack.objHash) == null ? "N/A" : AgentManager.getAgentName(pack.objHash);

                            if (name.equals("N/A") && pack.message.endsWith("connected.")) {
                                int idx = pack.message.indexOf("connected");
                                if (pack.message.indexOf("reconnected") > -1) {
                                    name = pack.message.substring(0, idx - 6);
                                } else {
                                    name = pack.message.substring(0, idx - 4);
                                }
                            }

                            // Make email subject
                            String subject = "[" + AlertLevel.getName(pack.level) + "] " + pack.objType.toUpperCase() + "(" + name + ") : " + pack.title;

                            String title = pack.title;
                            String msg = pack.message;
                            if (title.equals("INACTIVE_OBJECT")) {
                                title = "An object has been inactivated.";
                                msg = pack.message.substring(0, pack.message.indexOf("OBJECT") - 1);
                            }

                            try {
                                String ignoreNamePattern = conf.getValue("ext_plugin_ignore_name_patterns");
                                String ignoreTitlePattern = conf.getValue("ext_plugin_ignore_title_patterns");
                                String ignoreMessagePattern = conf.getValue("ext_plugin_ignore_message_patterns");

                                if (ignoreNamePattern != null && !"".equals(ignoreNamePattern)) {
                                    for (String pattern : ignoreNamePattern.split(",")) {
                                        if (name.matches(pattern.replaceAll("\\*", ".*"))) {
                                            return;
                                        }
                                    }
                                }

                                if (ignoreTitlePattern != null && !"".equals(ignoreTitlePattern)) {
                                    for (String pattern : ignoreTitlePattern.split(",")) {
                                        if (title.matches(pattern.replaceAll("\\*", ".*"))) {
                                            return;
                                        }
                                    }
                                }

                                if (ignoreMessagePattern != null && !"".equals(ignoreMessagePattern)) {
                                    for (String pattern : ignoreMessagePattern.split(",")) {
                                        if (msg.matches(pattern.replaceAll("\\*", ".*")
                                                .replaceAll("\\(", "\\\\(").replaceAll("\\)", "\\\\)")
                                                .replaceAll("\\[", "\\\\[").replaceAll("\\]", "\\\\]"))) {
                                            return;
                                        }
                                    }
                                }

                                if (conf.getBoolean("ext_plugin_ignore_continuous_dup_alert", false) && lastPack != null) {
                                    long diff = System.currentTimeMillis() - lastSentTimestamp;
                                    if (lastPack.objHash == pack.objHash && lastPack.title.equals(pack.title) && diff < DateUtil.MILLIS_PER_HOUR) {
                                        return;
                                    }
                                }

                                lastPack = pack;
                            } catch (Exception e) {
                                // ignore
                                println("[Error] : " + e.getMessage());
                            }

                            // Make email message
                            String message =    "[타입] : " + pack.objType.toUpperCase() + "\n" +
                                                "[서버명] : " + name + "\n" +
                                                "[레벨] : " + AlertLevel.getName(pack.level) + "\n" + 
                                                "[타이틀] : " + title + "\n" + 
                                                "[메세지] : " + msg;

                            // Create an Email instance
                            Email email = new SimpleEmail();

                            email.setHostName(hostname);
                            
                            if (sslEnabled) {
                                email.setSslSmtpPort(Integer.toString(port));
                            } else {
                                email.setSmtpPort(port);
                            }
                            
                            if (authEnabled) {
                                email.setAuthenticator(new DefaultAuthenticator(username, password));
                            }
                            email.setStartTLSEnabled(tlsEnabled);
                            email.setFrom(from);
                            email.setSubject(subject);
                            email.setMsg(message);

                            for (String addr : to.split(",")) {
                                email.addTo(addr);
                            }

                            if (cc != null) {
                                for (String addr : cc.split(",")) {
                                    email.addCc(addr);
                                }
                            }

                            // Send the email
                            email.send();

                            lastSentTimestamp = System.currentTimeMillis();
                            println("Email sent to [" + to + "] successfully.");
                        } catch (Exception e) {
                            println("[Error] : " + e.getMessage());

                            if (conf._trace) {
                                e.printStackTrace();
                            }
                        }
                    }
                }.start();
            }
        }
    }

    @ServerPlugin(PluginConstants.PLUGIN_SERVER_OBJECT)
    public void object(ObjectPack pack) {
        if (pack.version != null && pack.version.length() > 0) {
            AlertPack ap = null;
            ObjectPack op = AgentManager.getAgent(pack.objHash);

            if (op == null && pack.wakeup == 0L) {
                // in case of new agent connected
                ap = new AlertPack();
                ap.level = AlertLevel.INFO;
                ap.objHash = pack.objHash;
                ap.title = "오브젝트가 활성화 되었습니다.";
                ap.message = pack.objName + " 와 연결 되었습니다.";
                ap.time = System.currentTimeMillis();

                if (AgentManager.getAgent(pack.objHash) != null) {
                    ap.objType = AgentManager.getAgent(pack.objHash).objType;
                } else {
                    ap.objType = "scouter";
                }

                alert(ap);
            } else if (op.alive == false) {
                // in case of agent reconnected
                ap = new AlertPack();
                ap.level = AlertLevel.INFO;
                ap.objHash = pack.objHash;
                ap.title = "An object has been activated.";
                ap.message = pack.objName + " is reconnected.";
                ap.time = System.currentTimeMillis();
                ap.objType = AgentManager.getAgent(pack.objHash).objType;

                alert(ap);
            }
            // inactive state can be handled in alert() method.
        }
    }

    @ServerPlugin(PluginConstants.PLUGIN_SERVER_XLOG)
    public void xlog(XLogPack pack) {
        try {
            int elapsedThreshold = conf.getInt("ext_plugin_elapsed_time_threshold", 0);

            if (elapsedThreshold != 0 && pack.elapsed > elapsedThreshold) {
                String serviceName = TextRD.getString(DateUtil.yyyymmdd(pack.endTime), TextTypes.SERVICE, pack.service);

                AlertPack ap = new AlertPack();

                ap.level = AlertLevel.WARN;
                ap.objHash = pack.objHash;
                ap.title = "서비스 경과 시간(" + pack.elapsed + ")이 임계값(" + elapsedThreshold + ")을 초과했습니다.\n";
                ap.message = "에이전트 : [" + AgentManager.getAgentName(pack.objHash) + "]" + "\n" +
                             "서비스 : " + pack.service + "(" + serviceName + ")" + "\n" +
                             "경과시간(" + pack.elapsed + " ms)이 임계값(" + elapsedThreshold + ")을 초과했습니다.";
                ap.time = System.currentTimeMillis();
                ap.objType = AgentManager.getAgent(pack.objHash).objType;

                alert(ap);
            }else if(pack.error != 0) {
                String serviceName = TextRD.getString(DateUtil.yyyymmdd(pack.endTime), TextTypes.SERVICE, pack.service);

                String date = DateUtil.yyyymmdd(pack.endTime);
                String service = TextRD.getString(date, TextTypes.SERVICE, pack.service);
                AlertPack ap = new AlertPack();
                ap.level = AlertLevel.ERROR;
                ap.objHash = pack.objHash;
                ap.title = "xlog Error";
                ap.message = "서비스 에러 : " + pack.service + "(" + serviceName + ")" + "\n" +
                              TextRD.getString(date, TextTypes.ERROR, pack.error);
                ap.time = System.currentTimeMillis();
                ap.objType = AgentManager.getAgent(pack.objHash).objType;;
                alert(ap);

            }
        } catch (Exception e) {
            Logger.printStackTrace(e);
        }
    }

    @ServerPlugin(PluginConstants.PLUGIN_SERVER_COUNTER)
    public void counter(PerfCounterPack pack) {
        String objName = pack.objName;
        int objHash = HashUtil.hash(objName);
        String objType = null;
        String objFamily = null;

        if (AgentManager.getAgent(objHash) != null) {
            objType = AgentManager.getAgent(objHash).objType;
        }

        if (objType != null) {
            objFamily = CounterManager.getInstance().getCounterEngine().getObjectType(objType).getFamily().getName();
        }

        try {
            // in case of objFamily is javaee
            if (CounterConstants.FAMILY_JAVAEE.equals(objFamily)) {
                // save javaee type's objHash
                if (!javaeeObjHashList.contains(objHash)) {
                    javaeeObjHashList.add(objHash);
                }

                if (pack.timetype == TimeTypeEnum.REALTIME) {
                    long gcTimeThreshold = conf.getLong("ext_plugin_gc_time_threshold", 0);
                    long gcTime = pack.data.getLong(CounterConstants.JAVA_GC_TIME);

                    if (gcTimeThreshold != 0 && gcTime > gcTimeThreshold) {
                        AlertPack ap = new AlertPack();

                        ap.level = AlertLevel.WARN;
                        ap.objHash = objHash;
                        ap.title = "가비지 컬렉션 시간(" + gcTime + ")이 임계값(" + gcTimeThreshold + ")을 초과했습니다.";
                        ap.message = objName + "의 가비지 컬렉션 시간(" + gcTime + ")이 임계값(" + gcTimeThreshold + ")을 초과했습니다.";
                        ap.time = System.currentTimeMillis();
                        ap.objType = objType;

                        alert(ap);
                    }
                }
            }
        } catch (Exception e) {
            Logger.printStackTrace(e);
        }
    }

    private void println(Object o) {
        if (conf.getBoolean("ext_plugin_email_debug", false)) {
            Logger.println(o);
        }
    }
}
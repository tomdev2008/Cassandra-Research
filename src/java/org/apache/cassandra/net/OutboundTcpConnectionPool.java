/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;

import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.locator.IEndpointSnitch;
import org.apache.cassandra.metrics.ConnectionMetrics;
import org.apache.cassandra.security.SSLFactory;
import org.apache.cassandra.utils.FBUtilities;

public class OutboundTcpConnectionPool
{
    // pointer for the real Address.
    private final InetAddress id;
    public final OutboundTcpConnection cmdCon;
    public final OutboundTcpConnection ackCon;
    // pointer to the reseted Address.
    private InetAddress resetedEndpoint;
    private ConnectionMetrics metrics;

    //这个连接池其实只有cmdCon和ackCon两条连接
    //ackCon负责REQUEST_RESPONSE、INTERNAL_RESPONSE、GOSSIP
    //其他的由cmdCon负责
    OutboundTcpConnectionPool(InetAddress remoteEp)
    {
        /*
        CREATE TABLE peers (
            peer inet PRIMARY KEY, //对应remoteEp
            host_id uuid,
            tokens set<varchar>,
            schema_version uuid,
            release_version text,
            rpc_address inet,
            preferred_ip inet, //对应resetedEndpoint
            data_center text,
            rack text
         ) WITH COMMENT='known peers in the cluster'
         */
        id = remoteEp;
        resetedEndpoint = SystemKeyspace.getPreferredIP(remoteEp);

        cmdCon = new OutboundTcpConnection(this);
        cmdCon.start();
        ackCon = new OutboundTcpConnection(this);
        ackCon.start();

        metrics = new ConnectionMetrics(id, this);
    }

    /**
     * returns the appropriate connection based on message type.
     * returns null if a connection could not be established.
     */
    OutboundTcpConnection getConnection(MessageOut msg)
    {
        Stage stage = msg.getStage();
        return stage == Stage.REQUEST_RESPONSE || stage == Stage.INTERNAL_RESPONSE || stage == Stage.GOSSIP
               ? ackCon
               : cmdCon;
    }

    void reset()
    {
        for (OutboundTcpConnection conn : new OutboundTcpConnection[] { cmdCon, ackCon })
            conn.closeSocket(false);
    }

    public void resetToNewerVersion(int version)
    {
        for (OutboundTcpConnection conn : new OutboundTcpConnection[] { cmdCon, ackCon })
        {
            if (version > conn.getTargetVersion())
                conn.softCloseSocket();
        }
    }

    /**
     * reconnect to @param remoteEP (after the current message backlog is exhausted).
     * Used by Ec2MultiRegionSnitch to force nodes in the same region to communicate over their private IPs.
     * @param remoteEP
     */
    public void reset(InetAddress remoteEP)
    {
        SystemKeyspace.updatePreferredIP(id, remoteEP);
        resetedEndpoint = remoteEP;
        for (OutboundTcpConnection conn : new OutboundTcpConnection[] { cmdCon, ackCon })
            conn.softCloseSocket();

        // release previous metrics and create new one with reset address
        metrics.release();
        metrics = new ConnectionMetrics(resetedEndpoint, this);
    }

    public long getTimeouts()
    {
       return metrics.timeouts.count();
    }

    public long getRecentTimeouts()
    {
        return metrics.getRecentTimeout();
    }

    public void incrementTimeout()
    {
        metrics.timeouts.mark(); //加1
    }

    public Socket newSocket() throws IOException
    {
        return newSocket(endPoint());
    }

    public static Socket newSocket(InetAddress endpoint) throws IOException
    {
        // zero means 'bind on any available port.'
        if (isEncryptedChannel(endpoint))
        {
            if (Config.getOutboundBindAny())
                return SSLFactory.getSocket(DatabaseDescriptor.getServerEncryptionOptions(), endpoint, DatabaseDescriptor.getSSLStoragePort());
            else
                return SSLFactory.getSocket(DatabaseDescriptor.getServerEncryptionOptions(), endpoint, DatabaseDescriptor.getSSLStoragePort(), FBUtilities.getLocalAddress(), 0);
        }
        else
        {
            Socket socket = SocketChannel.open(new InetSocketAddress(endpoint, DatabaseDescriptor.getStoragePort())).socket();
            if (Config.getOutboundBindAny() && !socket.isBound())
                socket.bind(new InetSocketAddress(FBUtilities.getLocalAddress(), 0));
            return socket;
        }
    }

    public InetAddress endPoint()
    {
        if (id.equals(FBUtilities.getBroadcastAddress()))
            return FBUtilities.getLocalAddress();
        return resetedEndpoint == null ? id : resetedEndpoint;
    }

    public static boolean isEncryptedChannel(InetAddress address)
    {
        IEndpointSnitch snitch = DatabaseDescriptor.getEndpointSnitch();
        switch (DatabaseDescriptor.getServerEncryptionOptions().internode_encryption)
        {
            case none:
                return false; // if nothing needs to be encrypted then return immediately.
            case all:
                break;
            case dc: //数据中心相同时不需要加密
                if (snitch.getDatacenter(address).equals(snitch.getDatacenter(FBUtilities.getBroadcastAddress())))
                    return false;
                break;
            case rack: //数据中心和机架都相同时不需要加密
                // for rack then check if the DC's are the same.
                if (snitch.getRack(address).equals(snitch.getRack(FBUtilities.getBroadcastAddress()))
                        && snitch.getDatacenter(address).equals(snitch.getDatacenter(FBUtilities.getBroadcastAddress())))
                    return false;
                break;
        }
        return true;
    }

   public void close()
    {
        // these null guards are simply for tests
        if (ackCon != null)
            ackCon.closeSocket(true);
        if (cmdCon != null)
            cmdCon.closeSocket(true);
        metrics.release();
    }
}

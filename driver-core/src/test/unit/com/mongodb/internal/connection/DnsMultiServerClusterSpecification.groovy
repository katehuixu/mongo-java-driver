/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.internal.connection

import com.mongodb.MongoConfigurationException
import com.mongodb.ServerAddress
import com.mongodb.connection.ClusterId
import com.mongodb.connection.ClusterSettings
import com.mongodb.event.ClusterListener
import spock.lang.Specification

import static com.mongodb.connection.ClusterConnectionMode.MULTIPLE
import static com.mongodb.connection.ClusterType.SHARDED
import static com.mongodb.connection.ServerType.SHARD_ROUTER

class DnsMultiServerClusterSpecification extends Specification {

    private final ServerAddress firstServer = new ServerAddress('localhost:27017')
    private final ServerAddress secondServer = new ServerAddress('localhost:27018')
    private final ServerAddress thirdServer = new ServerAddress('localhost:27019')

    private final TestClusterableServerFactory factory = new TestClusterableServerFactory()

    def setup() {
        Time.makeTimeConstant()
    }

    def cleanup() {
        Time.makeTimeMove()
    }

    def 'should initialize from DNS SRV monitor'() {
        given:
        def srvHost = 'test1.test.build.10gen.cc'
        def clusterListener = Mock(ClusterListener)
        def dnsSrvRecordMonitor = Mock(DnsSrvRecordMonitor)
        def exception = new MongoConfigurationException('test')
        DnsSrvRecordInitializer initializer
        def dnsSrvRecordMonitorFactory = new DnsSrvRecordMonitorFactory() {
            @Override
            DnsSrvRecordMonitor create(final String hostName, final DnsSrvRecordInitializer dnsSrvRecordListener) {
                initializer = dnsSrvRecordListener
                dnsSrvRecordMonitor
            }
        }
        when: 'the cluster is constructed'
        def cluster = new DnsMultiServerCluster(new ClusterId(),
                ClusterSettings.builder()
                        .addClusterListener(clusterListener)
                        .srvHost(srvHost)
                        .mode(MULTIPLE)
                        .build(),
                factory, dnsSrvRecordMonitorFactory)

        then: 'the monitor is created and started'
        initializer != null
        1 * dnsSrvRecordMonitor.start()

        when: 'the listener is initialized with an exception'
        initializer.initialize(exception)

        then: 'the description includes the exception'
        cluster.getCurrentDescription().getServerDescriptions() == []
        cluster.getCurrentDescription().getSrvResolutionException() == exception

        when: 'the listener is initialized with servers'
        initializer.initialize([firstServer, secondServer] as Set)

        then: 'an event is generated'
        1 * clusterListener.clusterDescriptionChanged(_)

        when: 'the servers notify'
        factory.sendNotification(firstServer, SHARD_ROUTER)
        factory.sendNotification(secondServer, SHARD_ROUTER)
        def firstTestServer = factory.getServer(firstServer)
        def secondTestServer = factory.getServer(secondServer)
        def clusterDescription = cluster.getDescription()

        then: 'events are generated, description includes hosts, exception is cleared, and servers are open'
        2 * clusterListener.clusterDescriptionChanged(_)
        clusterDescription.getType() == SHARDED
        clusterDescription.getAll() == factory.getDescriptions(firstServer, secondServer)
        clusterDescription.getSrvResolutionException() == null
        !firstTestServer.isClosed()
        !secondTestServer.isClosed()

        when: 'the listener is initialized with a different server'
        initializer.initialize([secondServer, thirdServer])
        factory.sendNotification(secondServer, SHARD_ROUTER)
        def thirdTestServer = factory.getServer(thirdServer)
        clusterDescription = cluster.getDescription()

        then: 'events are generated, description is updated, and the removed server is closed'
        2 * clusterListener.clusterDescriptionChanged(_)
        clusterDescription.getType() == SHARDED
        clusterDescription.getAll() == factory.getDescriptions(secondServer, thirdServer)
        clusterDescription.getSrvResolutionException() == null
        firstTestServer.isClosed()
        !secondTestServer.isClosed()
        !thirdTestServer.isClosed()

        when: 'the listener is initialized with another exception'
        initializer.initialize(exception)
        clusterDescription = cluster.getDescription()

        then: 'the exception is ignored'
        0 * clusterListener.clusterDescriptionChanged(_)
        clusterDescription.getType() == SHARDED
        clusterDescription.getAll() == factory.getDescriptions(secondServer, thirdServer)
        clusterDescription.getSrvResolutionException() == null
        firstTestServer.isClosed()
        !secondTestServer.isClosed()
        !thirdTestServer.isClosed()

        when: 'the cluster is closed'
        cluster.close()

        then: 'the monitor is closed'
        1 * dnsSrvRecordMonitor.close()
    }
}

/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.mailbox.cassandra.mail;

import static com.datastax.driver.core.querybuilder.QueryBuilder.insertInto;
import static org.apache.james.backends.cassandra.Scenario.Builder.awaitOn;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.james.backends.cassandra.CassandraCluster;
import org.apache.james.backends.cassandra.CassandraClusterExtension;
import org.apache.james.backends.cassandra.Scenario.Barrier;
import org.apache.james.backends.cassandra.init.configuration.CassandraConfiguration;
import org.apache.james.backends.cassandra.init.configuration.CassandraConsistenciesConfiguration;
import org.apache.james.backends.cassandra.utils.CassandraUtils;
import org.apache.james.mailbox.cassandra.modules.CassandraAclModule;
import org.apache.james.mailbox.cassandra.table.CassandraACLTable;
import org.apache.james.mailbox.model.MailboxACL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CassandraACLMapperV1Test extends CassandraACLMapperContract {
    @RegisterExtension
    static CassandraClusterExtension cassandraCluster = new CassandraClusterExtension(CassandraAclModule.MODULE);

    private CassandraACLMapper cassandraACLMapper;

    @BeforeEach
    void setUp(CassandraCluster cassandra) {
        cassandraACLMapper =  new CassandraACLMapper(
            new CassandraUserMailboxRightsDAO(cassandra.getConf(), CassandraUtils.WITH_DEFAULT_CONFIGURATION),
            new CassandraACLDAOV1(cassandra.getConf(), CassandraConfiguration.DEFAULT_CONFIGURATION, CassandraConsistenciesConfiguration.DEFAULT));
    }

    @Override
    CassandraACLMapper cassandraACLMapper() {
        return cassandraACLMapper;
    }

    @Test
    void retrieveACLWhenInvalidInBaseShouldReturnEmptyACL(CassandraCluster cassandra) {
        cassandra.getConf().execute(
            insertInto(CassandraACLTable.TABLE_NAME)
                .value(CassandraACLTable.ID, MAILBOX_ID.asUuid())
                .value(CassandraACLTable.ACL, "{\"entries\":{\"bob\":invalid}}")
                .value(CassandraACLTable.VERSION, 1));

        assertThat(cassandraACLMapper.getACL(MAILBOX_ID).block()).isEqualTo(MailboxACL.EMPTY);
    }

    @Test
    void updateInvalidACLShouldBeBasedOnEmptyACL(CassandraCluster cassandra) throws Exception {
        cassandra.getConf().execute(
            insertInto(CassandraACLTable.TABLE_NAME)
                .value(CassandraACLTable.ID, MAILBOX_ID.asUuid())
                .value(CassandraACLTable.ACL, "{\"entries\":{\"bob\":invalid}}")
                .value(CassandraACLTable.VERSION, 1));
        MailboxACL.EntryKey key = new MailboxACL.EntryKey("bob", MailboxACL.NameType.user, false);
        MailboxACL.Rfc4314Rights rights = new MailboxACL.Rfc4314Rights(MailboxACL.Right.Read);

        cassandraACLMapper.updateACL(MAILBOX_ID, MailboxACL.command().key(key).rights(rights).asAddition()).block();

        assertThat(cassandraACLMapper.getACL(MAILBOX_ID).block()).isEqualTo(new MailboxACL().union(key, rights));
    }

    @Test
    void twoConcurrentUpdatesWhenNoACLStoredShouldReturnACLWithTwoEntries(CassandraCluster cassandra) throws Exception {
        Barrier barrier = new Barrier(2);
        cassandra.getConf()
            .registerScenario(awaitOn(barrier)
                .thenExecuteNormally()
                .times(2)
                .whenQueryStartsWith("SELECT acl,version FROM acl WHERE id=:id;"));

        MailboxACL.EntryKey keyBob = new MailboxACL.EntryKey("bob", MailboxACL.NameType.user, false);
        MailboxACL.Rfc4314Rights rights = new MailboxACL.Rfc4314Rights(MailboxACL.Right.Read);
        MailboxACL.EntryKey keyAlice = new MailboxACL.EntryKey("alice", MailboxACL.NameType.user, false);
        Future<Boolean> future1 = performACLUpdateInExecutor(cassandra, executor, keyBob, rights);
        Future<Boolean> future2 = performACLUpdateInExecutor(cassandra, executor, keyAlice, rights);

        barrier.awaitCaller();
        barrier.releaseCaller();

        awaitAll(future1, future2);

        assertThat(cassandraACLMapper.getACL(MAILBOX_ID).block())
            .isEqualTo(new MailboxACL().union(keyBob, rights).union(keyAlice, rights));
    }

    @Test
    void twoConcurrentUpdatesWhenStoredShouldReturnACLWithTwoEntries(CassandraCluster cassandra) throws Exception {
        MailboxACL.EntryKey keyBenwa = new MailboxACL.EntryKey("benwa", MailboxACL.NameType.user, false);
        MailboxACL.Rfc4314Rights rights = new MailboxACL.Rfc4314Rights(MailboxACL.Right.Read);
        cassandraACLMapper.updateACL(MAILBOX_ID, MailboxACL.command().key(keyBenwa).rights(rights).asAddition()).block();

        Barrier barrier = new Barrier(2);
        cassandra.getConf()
            .registerScenario(awaitOn(barrier)
                .thenExecuteNormally()
                .times(2)
                .whenQueryStartsWith("SELECT acl,version FROM acl WHERE id=:id;"));

        MailboxACL.EntryKey keyBob = new MailboxACL.EntryKey("bob", MailboxACL.NameType.user, false);
        MailboxACL.EntryKey keyAlice = new MailboxACL.EntryKey("alice", MailboxACL.NameType.user, false);
        Future<Boolean> future1 = performACLUpdateInExecutor(cassandra, executor, keyBob, rights);
        Future<Boolean> future2 = performACLUpdateInExecutor(cassandra, executor, keyAlice, rights);

        barrier.awaitCaller();
        barrier.releaseCaller();

        awaitAll(future1, future2);

        assertThat(cassandraACLMapper.getACL(MAILBOX_ID).block())
            .isEqualTo(new MailboxACL().union(keyBob, rights).union(keyAlice, rights).union(keyBenwa, rights));
    }

    private void awaitAll(Future<?>... futures) 
            throws InterruptedException, ExecutionException, TimeoutException {
        for (Future<?> future : futures) {
            future.get(10L, TimeUnit.SECONDS);
        }
    }

    private Future<Boolean> performACLUpdateInExecutor(CassandraCluster cassandra, ExecutorService executor, MailboxACL.EntryKey key, MailboxACL.Rfc4314Rights rights) {
        return executor.submit(() -> {
            CassandraACLMapper aclMapper = new CassandraACLMapper(
                new CassandraUserMailboxRightsDAO(cassandra.getConf(), CassandraUtils.WITH_DEFAULT_CONFIGURATION),
                new CassandraACLDAOV1(cassandra.getConf(),
                    CassandraConfiguration.DEFAULT_CONFIGURATION,
                    cassandraCluster.getCassandraConsistenciesConfiguration()));

            aclMapper.updateACL(MAILBOX_ID, MailboxACL.command().key(key).rights(rights).asAddition()).block();
            return true;
        });
    }

}
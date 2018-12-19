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

package org.apache.james.mailbox.store.event;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import javax.inject.Inject;

import org.apache.james.core.User;
import org.apache.james.core.quota.QuotaCount;
import org.apache.james.core.quota.QuotaSize;
import org.apache.james.mailbox.Event;
import org.apache.james.mailbox.MailboxListener;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.acl.ACLDiff;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.MessageMoves;
import org.apache.james.mailbox.model.Quota;
import org.apache.james.mailbox.model.QuotaRoot;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.apache.james.mailbox.store.mail.model.Mailbox;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

/**
 * Helper class to dispatch {@link org.apache.james.mailbox.Event}'s to registerend MailboxListener
 */
public class MailboxEventDispatcher {
    @VisibleForTesting
    public static MailboxEventDispatcher ofListener(MailboxListener mailboxListener) {
        return new MailboxEventDispatcher(mailboxListener);
    }

    private final MailboxListener listener;

    @Inject
    public MailboxEventDispatcher(DelegatingMailboxListener delegatingMailboxListener) {
        this((MailboxListener) delegatingMailboxListener);
    }

    private MailboxEventDispatcher(MailboxListener listener) {
        this.listener = listener;
    }

    /**
     * Should get called when a new message was added to a Mailbox. All
     * registered MailboxListener will get triggered then
     *
     * @param session The mailbox session
     * @param uids Sorted map with uids and message meta data
     * @param mailbox The mailbox
     */
    public void added(MailboxSession session, SortedMap<MessageUid, MessageMetaData> uids, Mailbox mailbox) {
        event(EventFactory.added()
            .mailbox(mailbox)
            .mailboxSession(session)
            .addMetaData(uids.values())
            .build());
    }

    public void added(MailboxSession session, Mailbox mailbox, MailboxMessage mailboxMessage) {
        MessageMetaData messageMetaData = mailboxMessage.metaData();
        SortedMap<MessageUid, MessageMetaData> metaDataMap = ImmutableSortedMap.<MessageUid, MessageMetaData>naturalOrder()
                .put(messageMetaData.getUid(), messageMetaData)
                .build();
        added(session, metaDataMap, mailbox);
    }

    public void added(MailboxSession session, MessageMetaData messageMetaData, Mailbox mailbox) {
        SortedMap<MessageUid, MessageMetaData> metaDataMap = ImmutableSortedMap.<MessageUid, MessageMetaData>naturalOrder()
            .put(messageMetaData.getUid(), messageMetaData)
            .build();
        added(session, metaDataMap, mailbox);
    }

    /**
     * Should get called when a message was expunged from a Mailbox. All
     * registered MailboxListener will get triggered then
     *
     * @param session The mailbox session
     * @param uids Sorted map with uids and message meta data
     * @param mailbox The mailbox
     */
    public void expunged(MailboxSession session,  Map<MessageUid, MessageMetaData> uids, Mailbox mailbox) {
        event(EventFactory.expunged()
            .mailbox(mailbox)
            .mailboxSession(session)
            .addMetaData(uids.values())
            .build());
    }

    public void expunged(MailboxSession session,  MessageMetaData messageMetaData, Mailbox mailbox) {
        Map<MessageUid, MessageMetaData> metaDataMap = ImmutableMap.<MessageUid, MessageMetaData>builder()
            .put(messageMetaData.getUid(), messageMetaData)
            .build();
        expunged(session, metaDataMap, mailbox);
    }

    /**
     * Should get called when the message flags were update in a Mailbox. All
     * registered MailboxListener will get triggered then
     */
    public void flagsUpdated(MailboxSession session, Mailbox mailbox, List<UpdatedFlags> uflags) {
        event(EventFactory.flagsUpdated()
            .mailbox(mailbox)
            .mailboxSession(session)
            .updatedFags(uflags)
            .build());
    }

    public void flagsUpdated(MailboxSession session, Mailbox mailbox, UpdatedFlags uflags) {
        flagsUpdated(session, mailbox, ImmutableList.of(uflags));
    }

    /**
     * Should get called when a Mailbox was renamed. All registered
     * MailboxListener will get triggered then
     */
    public void mailboxRenamed(MailboxSession session, MailboxPath from, Mailbox to) {
        event(EventFactory.mailboxRenamed()
            .mailboxSession(session)
            .mailboxId(to.getMailboxId())
            .oldPath(from)
            .newPath(to.generateAssociatedPath())
            .build());
    }

    /**
     * Should get called when a Mailbox was deleted. All registered
     * MailboxListener will get triggered then
     */
    public void mailboxDeleted(MailboxSession session, Mailbox mailbox, QuotaRoot quotaRoot, QuotaCount deletedMessageCount, QuotaSize totalDeletedSize) {
        event(EventFactory.mailboxDeleted()
            .mailboxSession(session)
            .mailbox(mailbox)
            .quotaRoot(quotaRoot)
            .deletedMessageCount(deletedMessageCount)
            .totalDeletedSize(totalDeletedSize)
            .build());
    }

    /**
     * Should get called when a Mailbox was added. All registered
     * MailboxListener will get triggered then
     */
    public void mailboxAdded(MailboxSession session, Mailbox mailbox) {
        event(EventFactory.mailboxAdded()
            .mailbox(mailbox)
            .mailboxSession(session)
            .build());
    }

    public void aclUpdated(MailboxSession session, MailboxPath mailboxPath, ACLDiff aclDiff, MailboxId mailboxId) {
        event(EventFactory.aclUpdated()
            .mailboxSession(session)
            .path(mailboxPath)
            .mailboxId(mailboxId)
            .aclDiff(aclDiff)
            .build());
    }

    public void moved(MailboxSession session, MessageMoves messageMoves, Collection<MessageId> messageIds) {
        event(EventFactory.moved()
            .session(session)
            .messageMoves(messageMoves)
            .messageId(messageIds)
            .build());
    }

    public void quota(User user, QuotaRoot quotaRoot, Quota<QuotaCount> countQuota, Quota<QuotaSize> sizeQuota) {
        event(new MailboxListener.QuotaUsageUpdatedEvent(user, quotaRoot, countQuota, sizeQuota, Instant.now()));
    }

    public void event(Event event) {
        listener.event(event);
    }
}

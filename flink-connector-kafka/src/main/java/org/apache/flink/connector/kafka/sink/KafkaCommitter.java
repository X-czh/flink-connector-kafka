/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.kafka.sink;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.connector.sink2.Committer;
import org.apache.flink.connector.kafka.sink.internal.BackchannelFactory;
import org.apache.flink.connector.kafka.sink.internal.FlinkKafkaInternalProducer;
import org.apache.flink.connector.kafka.sink.internal.WritableBackchannel;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.IOUtils;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.InvalidTxnStateException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.UnknownProducerIdException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.Properties;

/**
 * Committer implementation for {@link KafkaSink}
 *
 * <p>The committer is responsible to finalize the Kafka transactions by committing them.
 */
class KafkaCommitter implements Committer<KafkaCommittable>, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaCommitter.class);
    public static final String UNKNOWN_PRODUCER_ID_ERROR_MESSAGE =
            "because of a bug in the Kafka broker (KAFKA-9310). Please upgrade to Kafka 2.5+. If you are running with concurrent checkpoints, you also may want to try without them.\n"
                    + "To avoid data loss, the application will restart.";

    private final Properties kafkaProducerConfig;
    private final WritableBackchannel<String> backchannel;
    @Nullable private FlinkKafkaInternalProducer<?, ?> committingProducer;

    KafkaCommitter(
            Properties kafkaProducerConfig,
            String transactionalIdPrefix,
            int subtaskId,
            int attemptNumber) {
        this.kafkaProducerConfig = kafkaProducerConfig;
        backchannel =
                BackchannelFactory.getInstance()
                        .getWritableBackchannel(subtaskId, attemptNumber, transactionalIdPrefix);
    }

    @VisibleForTesting
    public WritableBackchannel<String> getBackchannel() {
        return backchannel;
    }

    @Override
    public void commit(Collection<CommitRequest<KafkaCommittable>> requests)
            throws IOException, InterruptedException {
        for (CommitRequest<KafkaCommittable> request : requests) {
            final KafkaCommittable committable = request.getCommittable();
            final String transactionalId = committable.getTransactionalId();
            LOG.debug("Committing Kafka transaction {}", transactionalId);
            Optional<FlinkKafkaInternalProducer<?, ?>> writerProducer = committable.getProducer();
            FlinkKafkaInternalProducer<?, ?> producer;
            try {
                producer = writerProducer.orElseGet(() -> getProducer(committable));
                producer.commitTransaction();
                backchannel.send(committable.getTransactionalId());
            } catch (RetriableException e) {
                LOG.warn(
                        "Encountered retriable exception while committing {}.", transactionalId, e);
                request.retryLater();
            } catch (ProducerFencedException e) {
                // initTransaction has been called on this transaction before
                LOG.error(
                        "Unable to commit transaction ({}) because its producer is already fenced."
                                + " This means that you either have a different producer with the same '{}' (this is"
                                + " unlikely with the '{}' as all generated ids are unique and shouldn't be reused)"
                                + " or recovery took longer than '{}' ({}ms). In both cases this most likely signals data loss,"
                                + " please consult the Flink documentation for more details.",
                        request,
                        ProducerConfig.TRANSACTIONAL_ID_CONFIG,
                        KafkaSink.class.getSimpleName(),
                        ProducerConfig.TRANSACTION_TIMEOUT_CONFIG,
                        kafkaProducerConfig.getProperty(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG),
                        e);
                backchannel.send(committable.getTransactionalId());
                request.signalFailedWithKnownReason(e);
            } catch (InvalidTxnStateException e) {
                // This exception only occurs when aborting after a commit or vice versa.
                // It does not appear on double commits or double aborts.
                LOG.error(
                        "Unable to commit transaction ({}) because it's in an invalid state. "
                                + "Most likely the transaction has been aborted for some reason. Please check the Kafka logs for more details.",
                        request,
                        e);
                backchannel.send(committable.getTransactionalId());
                request.signalFailedWithKnownReason(e);
            } catch (UnknownProducerIdException e) {
                LOG.error(
                        "Unable to commit transaction ({}) " + UNKNOWN_PRODUCER_ID_ERROR_MESSAGE,
                        request,
                        e);
                backchannel.send(committable.getTransactionalId());
                request.signalFailedWithKnownReason(e);
            } catch (Exception e) {
                LOG.error(
                        "Transaction ({}) encountered error and data has been potentially lost.",
                        request,
                        e);
                // cause failover
                request.signalFailedWithUnknownReason(e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            IOUtils.closeAll(backchannel, committingProducer);
        } catch (Exception e) {
            ExceptionUtils.rethrow(e);
        }
    }

    /**
     * Creates a producer that can commit into the same transaction as the upstream producer that
     * was serialized into {@link KafkaCommittable}.
     */
    private FlinkKafkaInternalProducer<?, ?> getProducer(KafkaCommittable committable) {
        if (committingProducer == null) {
            committingProducer =
                    new FlinkKafkaInternalProducer<>(
                            kafkaProducerConfig, committable.getTransactionalId());
        } else {
            committingProducer.setTransactionId(committable.getTransactionalId());
        }
        committingProducer.resumeTransaction(committable.getProducerId(), committable.getEpoch());
        return committingProducer;
    }
}

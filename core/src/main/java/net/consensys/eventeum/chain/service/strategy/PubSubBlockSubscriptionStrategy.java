package net.consensys.eventeum.chain.service.strategy;

import io.reactivex.disposables.Disposable;
import lombok.Setter;
import net.consensys.eventeum.chain.service.BlockchainException;
import net.consensys.eventeum.chain.service.domain.Block;
import net.consensys.eventeum.chain.service.domain.wrapper.Web3jBlock;
import net.consensys.eventeum.dto.block.BlockDetails;
import net.consensys.eventeum.model.LatestBlock;
import net.consensys.eventeum.service.EventStoreService;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.websocket.events.NewHead;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PubSubBlockSubscriptionStrategy extends AbstractBlockSubscriptionStrategy<NewHead> {

    private Lock lock = new ReentrantLock();

    private RetryTemplate retryTemplate;

    public PubSubBlockSubscriptionStrategy(Web3j web3j, String nodeName, EventStoreService eventStoreService) {
        super(web3j, nodeName, eventStoreService);
    }

    @Override
    public Disposable subscribe() {
        final Optional<LatestBlock> latestBlock = getLatestBlock();

        if (latestBlock.isPresent()) {
            final DefaultBlockParameter blockParam = DefaultBlockParameter.valueOf(latestBlock.get().getNumber());

            //New heads can only start from latest block so we need to obtain missing blocks first
            web3j.replayPastAndFutureBlocksFlowable(blockParam, false)
                    .doOnComplete(() -> blockSubscription = subscribeToNewHeads())
                    .subscribe(ethBlock -> triggerListeners(convertToNewHead(ethBlock)));
        } else {
            blockSubscription = subscribeToNewHeads();
        }

        return blockSubscription;
    }

    private Disposable subscribeToNewHeads() {
        return web3j.newHeadsNotifications().subscribe(newHead -> {
            triggerListeners(newHead.getParams().getResult());
        });
    }

    NewHead convertToNewHead(EthBlock ethBlock) {
        final BasicNewHead newHead = new BasicNewHead();
        newHead.setHash(ethBlock.getBlock().getHash());
        newHead.setNumber(ethBlock.getBlock().getNumberRaw());
        newHead.setTimestamp(ethBlock.getBlock().getTimestampRaw());

        return newHead;
    }

    @Override
    Block convertToEventeumBlock(NewHead blockObject) {
        return new Web3jBlock(getEthBlock(blockObject.getHash()).getBlock(), nodeName);
    }

    protected RetryTemplate getRetryTemplate() {
        if (retryTemplate == null) {
            retryTemplate = new RetryTemplate();

            final FixedBackOffPolicy fixedBackOffPolicy = new FixedBackOffPolicy();
            fixedBackOffPolicy.setBackOffPeriod(500);
            retryTemplate.setBackOffPolicy(fixedBackOffPolicy);

            final SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
            retryPolicy.setMaxAttempts(3);
            retryTemplate.setRetryPolicy(retryPolicy);
        }

        return retryTemplate;
    }

    private EthBlock getEthBlock(String blockHash) {
        return getRetryTemplate().execute((context) -> {
            try {
                final EthBlock block = web3j.ethGetBlockByHash(blockHash, true).send();

                if (block == null || block.getBlock() == null) {
                    throw new BlockchainException("Block not found");
                }

                return block;
            } catch (IOException e) {
                throw new BlockchainException("Unable to retrieve block details", e);
            }
        });
    }

    @Setter
    private class BasicNewHead extends NewHead {
        private String hash;

        private String number;

        private String timestamp;

        @Override
        public String getHash() {
            return hash;
        }

        @Override
        public String getNumber() {
            return number;
        }

        @Override
        public String getTimestamp() {
            return timestamp;
        }
    }
}

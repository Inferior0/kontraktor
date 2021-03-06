package tck;

import org.nustaq.kontraktor.reactivestreams.KxReactiveStreams;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.tck.SubscriberBlackboxVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

/**
 * Created by ruedi on 03/07/15.
 */
@Test
public class TCKSubscriberBlackBox extends SubscriberBlackboxVerification<Long> {

    public TCKSubscriberBlackBox() {
        super(new TestEnvironment(300l));
    }

    @Override
    public Subscriber<Long> createSubscriber() {
        // need to use ridiculous small batchsize here, don not use such small batch sizes
        // in an applciation 10k to 50k should be capable to overcome request(N) latency
        // and avoid the sender runnning dry
        return KxReactiveStreams.get().subscriber( 4, (res,err) ->  {
        });
    }

    @Override
    public Long createElement(int element) {
        return Long.valueOf(Integer.toString(element));
    }

    @Override
    public Publisher<Long> createHelperPublisher(long elements) {
        return TCKSyncPubEventSink.createRangePublisher(elements);
    }
}

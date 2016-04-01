package org.nustaq.reallive.impl.actors;

import org.nustaq.kontraktor.*;
import org.nustaq.kontraktor.annotations.CallerSideMethod;
import org.nustaq.kontraktor.annotations.Local;
import org.nustaq.kontraktor.impl.CallbackWrapper;
import org.nustaq.kontraktor.remoting.encoding.CallbackRefSerializer;
import org.nustaq.kontraktor.util.Log;
import org.nustaq.reallive.impl.FilterProcessorImpl;
import org.nustaq.reallive.impl.storage.StorageStats;
import org.nustaq.reallive.interfaces.*;
import org.nustaq.reallive.impl.Mutator;
import org.nustaq.reallive.impl.StorageDriver;

import java.util.HashMap;
import java.util.function.*;

/**
 * Created by ruedi on 06.08.2015.
 *
 * core implementation of a table
 *
 * FIXME: missing
 * - CAS/updateActions
 * - originator
 *
 *
 */
public class RealLiveStreamActor<K> extends Actor<RealLiveStreamActor<K>> implements RealLiveTable<K>, Mutatable<K> {

    StorageDriver<K> storageDriver;
    FilterProcessor<K> filterProcessor[];
    HashMap<String,Subscriber> receiverSideSubsMap = new HashMap();
    TableDescription description;

    int roundRobinFilter = 0;

    @Local
    public void init( Supplier<RecordStorage<K>> storeFactory, TableDescription desc) {
        this.description = desc;
        Thread.currentThread().setName("Table "+desc.getName()+" main");
        RecordStorage<K> store = storeFactory.get();
        storageDriver = new StorageDriver<>(store);
        if ( desc.filterThreads() > 0 ) {
            filterProcessor = new FilterProcessor[desc.filterThreads()];
            for (int i = 0; i < filterProcessor.length; i++) {
                FilterProcessor<K> kFilterProcessor = Actors.AsActor(FilterProcessorActor.class);
                ((FilterProcessorActor) kFilterProcessor).init(self(),desc.getName()+"-filter_"+i);
                filterProcessor[i] = kFilterProcessor;
            }
        } else {
            filterProcessor = new FilterProcessor[] { new FilterProcessorImpl<>(this) };
        }
        storageDriver.setListener( change -> {
            for (int i = 0; i < filterProcessor.length; i++) {
                filterProcessor[i].receive(change);
            }
        });
    }


    @Override
    public void receive(ChangeMessage<K> change) {
        checkThread();
        try {
            storageDriver.receive(change);
        } catch (Exception th) {
            Log.Error(this,th);
        }
    }

    @Override
    public <T> void forEach(Spore<Record<K>, T> spore) {
        checkThread();
        try {
            storageDriver.getStore().forEach(spore);
        } catch (Exception ex) {
            spore.complete(null,ex);
        }
    }

    @Override
    protected void hasStopped() {
    }

    // subscribe/unsubscribe
    // on callerside, the subscription is decomposed to kontraktor primitives
    // and a subscription id (locally unique)
    // remote receiver then builds a unique id by concatening localid#connectionId

    @Override
    @CallerSideMethod public void subscribe(Subscriber<K> subs) {
        // need callerside to transform to Callback
        Callback callback = (r, e) -> {
            if (Actors.isResult(e))
                subs.getReceiver().receive((ChangeMessage<K>) r);
        };
        _subscribe(subs.getFilter(), callback, subs.getId());
    }

    public void _subscribe(RLPredicate pred, Callback cb, int id ) {
        checkThread();
        Subscriber localSubs = new Subscriber(pred, change -> {
            cb.stream(change);
            // disconnects ..
            //if (change.isDoneMsg())
            //    cb.finish();
        }).serverSideCB(cb);
        String sid = addChannelIdIfPresent(cb, ""+id);
        receiverSideSubsMap.put(sid,localSubs);
        filterProcessor[roundRobinFilter].subscribe(localSubs);
        roundRobinFilter = roundRobinFilter+1;
        if ( roundRobinFilter >= filterProcessor.length ) {
            roundRobinFilter = 0;
        }
    }

    protected String addChannelIdIfPresent(Callback cb, String sid) {
        if ( cb instanceof CallbackWrapper && ((CallbackWrapper) cb).isRemote() ) {
            // hack to get unique id sender#connection
            CallbackRefSerializer.MyRemotedCallback realCallback
                = (CallbackRefSerializer.MyRemotedCallback) ((CallbackWrapper) cb).getRealCallback();
            sid += "#"+realCallback.getChanId();
        }
        return sid;
    }

    @CallerSideMethod @Override
    public void unsubscribe(Subscriber<K> subs) {
        _unsubscribe( (r,e) -> {}, subs.getId() );
    }

    public void _unsubscribe( Callback cb /*dummy required to find sending connection*/, int id ) {
        checkThread();
        String sid = addChannelIdIfPresent(cb, ""+id);
        Subscriber<K> subs = (Subscriber<K>) receiverSideSubsMap.get(sid);
        // fixme: could memoize associated filterprocessor
        for (int i = 0; i < filterProcessor.length; i++) {
            FilterProcessor<K> kFilterProcessor = filterProcessor[i];
            kFilterProcessor.unsubscribe(subs);
        }
        receiverSideSubsMap.remove(sid);
        cb.finish();
        subs.getServerSideCB().finish();
    }

    @Override
    public IPromise<Record<K>> get(K key) {
        return resolve(storageDriver.getStore().get(key));
    }

    @Override
    public IPromise<Long> size() {
        return resolve(storageDriver.getStore().size());
    }

    @Override @CallerSideMethod
    public Mutation<K> getMutation() {
        return new Mutator<>(self());
    }

    @Override
    public IPromise<TableDescription> getDescription() {
        return resolve(description);
    }

    @Override
    public IPromise<StorageStats> getStats() {
        return resolve(storageDriver.getStore().getStats());
    }

    @Override
    public IPromise<Boolean> putCAS(RLPredicate<Record<K>> casCondition, K key, Object[] keyVals) {
        return storageDriver.putCAS(casCondition,key,keyVals);
    }

    @Override
    public void atomic(K key, RLConsumer<Record<K>> action) {
        storageDriver.atomic(key,action);
    }

}

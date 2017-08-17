package reactinstrinsic.servlet;

import org.nustaq.kontraktor.Actor;
import org.nustaq.kontraktor.IPromise;
import org.nustaq.kontraktor.Promise;
import org.nustaq.kontraktor.remoting.base.RemotedActor;
import org.nustaq.kontraktor.util.Log;

public class ReactServletSession extends Actor<ReactServletSession> implements RemotedActor {

    private String name;

    public void init(String name) {
        this.name = name;
    }

    public IPromise<String> greet(String who) {
        return new Promise("Hello "+who+" from "+name);
    }

    /**
     * interface RemotedActor, session time out notification callback
     */
    @Override
    public void hasBeenUnpublished() {
        Log.Info(this,"bye "+name);
    }
}

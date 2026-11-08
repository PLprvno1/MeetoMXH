package com.example.meetomxh.ui;

import com.example.meetomxh.models.Chat;
import com.example.meetomxh.supabase.ServerTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Tracks message changes independently of read markers and profile refreshes. */
public final class InboxNewMessages {
    private static final class State {
        final String messageId;
        final long sequence;
        final ServerTime time;
        State(Chat chat){messageId=chat.getLastMessageId();sequence=chat.highWater;time=chat.getLastMessageAt();}
    }
    private final Map<String,State> previous=new HashMap<>();
    private boolean initialized;

    public boolean update(List<Chat> visible){
        boolean changed=false;Map<String,State> next=new HashMap<>();
        for(Chat chat:visible){
            State before=previous.get(chat.getChatId()),now=new State(chat);
            if(initialized){
                if(before==null)changed=true;
                else if(now.messageId!=null&&!Objects.equals(now.messageId,before.messageId)
                    &&(now.sequence>before.sequence||(now.time!=null&&(before.time==null||now.time.compareTo(before.time)>0))))changed=true;
            }
            next.put(chat.getChatId(),now);
        }
        previous.clear();previous.putAll(next);initialized=true;return changed;
    }
    public void clear(){previous.clear();initialized=false;}
}

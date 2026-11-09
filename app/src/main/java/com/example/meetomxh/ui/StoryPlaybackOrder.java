package com.example.meetomxh.ui;

import com.example.meetomxh.models.Story;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Keeps the viewer's own stories first, matching their position in the story tray. */
public final class StoryPlaybackOrder {
    private StoryPlaybackOrder() {}

    public static List<Story> replaceAuthor(List<Story> discovery,List<Story> authorStories,String author,String self){
        int insertion=discovery.size();
        for(int i=0;i<discovery.size();i++)if(Objects.equals(author,discovery.get(i).getAuthorId())){insertion=i;break;}
        if(self!=null&&self.equalsIgnoreCase(author))insertion=0;
        List<Story> ordered=new ArrayList<>();
        for(Story story:discovery)if(!Objects.equals(author,story.getAuthorId()))ordered.add(story);
        ordered.addAll(Math.min(insertion,ordered.size()),authorStories);
        return ordered;
    }
}

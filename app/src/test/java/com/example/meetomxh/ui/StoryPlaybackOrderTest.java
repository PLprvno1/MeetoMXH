package com.example.meetomxh.ui;

import com.example.meetomxh.models.Story;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class StoryPlaybackOrderTest {
    private Story story(String id,String author){Story story=new Story();story.setId(id);story.setAuthorId(author);return story;}

    @Test public void ownNewestStoriesMoveBeforeOtherAuthors(){
        Story a=story("a","alice"),b=story("b","bob"),mine=story("mine","self"),second=story("second","self");
        List<Story> discovery=Arrays.asList(a,b,mine);
        assertEquals(Arrays.asList(mine,second,a,b),StoryPlaybackOrder.replaceAuthor(discovery,Arrays.asList(mine,second),"self","self"));
        assertEquals(Arrays.asList(a,b,mine),discovery);
    }
    @Test public void ownStoriesOutsideDiscoveryStillStartTheViewer(){
        Story a=story("a","alice"),mine=story("mine","self");
        assertEquals(Arrays.asList(mine,a),StoryPlaybackOrder.replaceAuthor(Collections.singletonList(a),Collections.singletonList(mine),"self","self"));
    }
    @Test public void otherAuthorsKeepTheirExistingPositionAndChronologicalStories(){
        Story mine=story("mine","self"),a=story("a","alice"),a2=story("a2","alice"),b=story("b","bob");
        assertEquals(Arrays.asList(mine,a,a2,b),StoryPlaybackOrder.replaceAuthor(Arrays.asList(mine,a,b,a2),Arrays.asList(a,a2),"alice","self"));
    }
    @Test public void noOwnStoriesDoesNotDuplicateOrInventAnEntry(){
        Story a=story("a","alice"),mine=story("expired","self");
        assertEquals(Collections.singletonList(a),StoryPlaybackOrder.replaceAuthor(Arrays.asList(a,mine),Collections.emptyList(),"self","self"));
    }
}

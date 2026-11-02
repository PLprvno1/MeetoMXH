package com.example.meetomxh.adapters;

import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.MainActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.models.ChatMessage;
import com.example.meetomxh.supabase.ServerTime;
import com.example.meetomxh.supabase.SessionManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import static org.junit.Assert.*;

/** Local presentation fixtures; this does not certify media verification or Storage reads. */
@RunWith(AndroidJUnit4.class)
public class MessagePresentationTest {
    @Test public void receiptsUseSequenceAndPrivateMediaReferencesUseRegistryProtocol() {
        MainActivity activity = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ChatMessage message = new ChatMessage();
            message.setMessageId("local-presentation-fixture");
            message.setSenderId(SessionManager.getInstance().uid());
            message.setType("text");
            message.setText("sequence receipt fixture");
            message.seq = 20;
            message.setCreatedAt(ServerTime.parse("2026-11-02T00:00:00Z"));
            ChatMessageAdapter adapter = new ChatMessageAdapter(SessionManager.getInstance().uid());
            adapter.submitList(Collections.singletonList(message));
            ChatMessageAdapter.MessageViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(activity), 0);
            adapter.setOtherParticipantLastReadSeq(19L);
            adapter.onBindViewHolder(holder, 0);
            TextView receipt = holder.itemView.findViewById(R.id.tvMessageReceipt);
            assertEquals("sent until peer ACK covers message sequence", "Đã gửi", receipt.getText().toString());
            adapter.setOtherParticipantLastReadSeq(20L);
            adapter.onBindViewHolder(holder, 0);
            assertEquals("read only after peer ACK covers message sequence", "Đã xem", receipt.getText().toString());
            // No registry row or ready marker is created for this client-side fixture.
            message.setType("image");
            message.setImageUrl("asset://00000000-0000-4000-8000-000000000011");
            adapter.onBindViewHolder(holder, 0);
            assertEquals(View.VISIBLE, holder.itemView.findViewById(R.id.ivMessageImage).getVisibility());
            message.setRecalled(true);
            adapter.onBindViewHolder(holder, 0);
            assertEquals(View.GONE, holder.itemView.findViewById(R.id.ivMessageImage).getVisibility());
            com.bumptech.glide.Glide.with(activity).clear((View) holder.itemView.findViewById(R.id.ivMessageImage));
        });
    }
}

package com.example.meetomxh.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.meetomxh.adapters.InboxAdapter;
import com.example.meetomxh.databinding.FragmentInboxBinding;
import com.example.meetomxh.models.Inbox;

import java.util.ArrayList;
import java.util.List;

public class InboxFragment extends Fragment {

    private FragmentInboxBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentInboxBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.rvInbox.setLayoutManager(new LinearLayoutManager(requireContext()));

        List<Inbox> dummyInboxList = new ArrayList<>();
        dummyInboxList.add(new Inbox(
                "1",
                "Nguyễn Văn A",
                "Chào bạn, dự án MeetoMXH tiến triển thế nào rồi?",
                "",
                "10:15"
        ));
        dummyInboxList.add(new Inbox(
                "2",
                "Trần Thị B",
                "Tối nay đi cafe không bạn ơi?",
                "",
                "09:30"
        ));
        dummyInboxList.add(new Inbox(
                "3",
                "Nhóm Học Lập Trình",
                "Lê Văn C: Cảm ơn thông tin của mọi người nhé!",
                "",
                "Hôm qua"
        ));
        dummyInboxList.add(new Inbox(
                "4",
                "Phạm Hoàng D",
                "Ok, nhận được thông tin rồi nha.",
                "",
                "05/10"
        ));

        InboxAdapter adapter = new InboxAdapter(dummyInboxList);
        binding.rvInbox.setAdapter(adapter);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

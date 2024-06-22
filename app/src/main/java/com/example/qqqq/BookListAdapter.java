package com.example.qqqq;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;

import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class BookListAdapter extends ArrayAdapter<BookItem> {
    private final Context context;
    private final List<BookItem> bookItems;
    private final List<String> selectedRfids; // 선택된 책의 RFID 번호 리스트

    public BookListAdapter(Context context, int resource, List<BookItem> bookItems) {
        super(context, resource, bookItems);
        this.context = context;
        this.bookItems = bookItems;
        this.selectedRfids = new ArrayList<>();
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.booklist, parent, false);
        }

        BookItem bookItem = getItem(position);

        TextView titleTextView = convertView.findViewById(R.id.book_title);
        CheckBox checkBox = convertView.findViewById(R.id.checkbox);

        titleTextView.setText(bookItem.getTitle());

        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedRfids.add(bookItem.getRfid());
            } else {
                selectedRfids.remove(bookItem.getRfid());
            }
        });

        return convertView;
    }

    public List<String> getSelectedRfids() {
        return selectedRfids;
    }
}
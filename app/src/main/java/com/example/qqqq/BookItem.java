package com.example.qqqq;

public class BookItem {
    private String title;
    private String rfid;

    public BookItem(String title, String rfid) {
        this.title = title;
        this.rfid = rfid;
    }

    public String getTitle() {
        return title;
    }

    public String getRfid() {
        return rfid;
    }
}

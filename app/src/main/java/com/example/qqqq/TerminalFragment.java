package com.example.qqqq;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import android.webkit.WebView;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    // 블루투스 기기의 주소
    private String deviceAddress;
    // SerialService와의 연결 관리
    private SerialService service;

    // 수신된 텍스트를 표시할 TextView
    private TextView receiveText;
    // 웹 페이지를 표시할 WebView
    private WebView webView;
    String url = "http://52.79.204.104/";
    String location;
    private TextView locationNumberTextView;

    // 수신된 데이터의 목록
    private List<String> receivedDataList;
    // 중복 체크를 위한 집합
    private Set<String> receivedDataSet;

    // 회원 인증 데이터와 책 대출 데이터 저장 변수
    private String userRfidNumber;
    private List<String> bookRfidNumbers; // 책 대출 데이터 리스트
    // 연결 상태를 나타내는 열거형
    private enum Connected { False, Pending, True }
    private Connected connected = Connected.False;

    // 초기 시작 여부
    private boolean initialStart = false;
    private static final long BLUETOOTH_CONNECTION_DELAY = 10000; // 블루투스 연결 지연 시간 (10초)
    private boolean bluetoothConnectionAllowed = false; // 블루투스 통신 허용 여부 플래그
    private boolean isAuthenticating = false; // 사용자 인증 중 여부 플래그
    // 헥스 모드 활성화 여부
    private boolean hexEnabled = false;
    // 줄 바꿈 문자
    private String newline = TextUtil.newline_crlf;

    // Current operation type
    private enum OperationType { NONE, AUTHENTICATION, BORROWING }
    private OperationType currentOperation = OperationType.NONE;

    // UI 요소: 버튼들
    Button bookPosition, btn_user, bookScan, admin;

    //관리자 모드 비밀번호 세팅
    private static final String ADMIN_PASSWORD = "1234"; // 관리자 비밀번호 설정

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true); // 옵션 메뉴 사용 설정
        setRetainInstance(true); // 인스턴스 상태 유지
        deviceAddress = getArguments().getString("device"); // 인텐트로부터 블루투스 기기 주소 가져오기
        receivedDataList = new ArrayList<>(); // 수신된 데이터 리스트 초기화
        receivedDataSet = new HashSet<>(); // 중복 체크를 위한 집합 초기화
        bookRfidNumbers = new ArrayList<>(); // 책 대출 데이터 리스트 초기화
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect(); // 연결 해제
        getActivity().stopService(new Intent(getActivity(), SerialService.class)); // 서비스 중지
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this); // 서비스 연결
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // 서비스 시작
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE); // 서비스 바인딩
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect); // 연결 시도
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService(); // 서비스 연결
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect); // 연결 시도
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null; // 서비스 연결 해제
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // 프래그먼트의 레이아웃을 생성하고 초기화
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // 텍스트 색상 설정
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance()); // 스크롤 가능하게 설정

        locationNumberTextView = view.findViewById(R.id.callNumberTextView);
        webView = view.findViewById(R.id.webView); // WebView 초기화
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true); // 자바스크립트 허용

        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl(url);

        // 버튼 초기화 및 클릭 리스너 설정
        bookPosition = view.findViewById(R.id.bookPosition);
        btn_user = view.findViewById(R.id.btn_user);
        bookScan = view.findViewById(R.id.bookScan);

        admin = view.findViewById(R.id.btn_admin);
        bookPosition.setOnClickListener(v -> {
            showBookLocation();
            send(location);
        });
        btn_user.setOnClickListener(v -> authenticateUser());
        bookScan.setOnClickListener(v -> scanBook());
        admin.setOnClickListener(v -> showPasswordDialog());
        return view;
    }

//관리자 모드
    private void showPasswordDialog() {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View view = inflater.inflate(R.layout.dialog_password, null);
        final EditText etPassword = view.findViewById(R.id.et_password);
        Button btnSubmit = view.findViewById(R.id.btn_submit);

        final AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(view)
                .create();

        btnSubmit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String password = etPassword.getText().toString();
                if (password.equals(ADMIN_PASSWORD)) {
                    dialog.dismiss();
                    bluetoothConnectionAllowed = true;
                    connect();

                    // 일정 시간 지연 후 데이터 전송
                    new Handler().postDelayed(() -> {
                        if (connected == Connected.True) {
                            send("zzzzzzzzzzzz");
                            Toast.makeText(getActivity(), "관리자 인증이 되었습니다.", Toast.LENGTH_SHORT).show();
                            disconnect();
                        } else {
                            Toast.makeText(getActivity(), "Bluetooth connection failed", Toast.LENGTH_SHORT).show();
                        }
                    }, 3000);
                } else {
                    Toast.makeText(getContext(), "비밀번호가 틀렸습니다.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        dialog.show();
    }


    private void connect() {
        if (!bluetoothConnectionAllowed) {
            return; // 블루투스 통신 허용되지 않은 경우 연결하지 않음
        }
        try {
            // 블루투스 어댑터 초기화 및 기기 주소로 블루투스 기기 객체 생성
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting..."); // 상태 업데이트
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket); // 소켓을 통한 서비스 연결 시도
        } catch (Exception e) {
            onSerialConnectError(e); // 연결 실패 시 예외 처리
        }
    }

    private void disconnect() {
        connected = Connected.False; // 연결 상태 업데이트
        service.disconnect(); // 서비스 연결 해제
    }

    private void send(String str) {
        if(connected != Connected.True) {
          //  Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }
    //서버로 데이터 전송 메서드
    public void sendLendingRequest(String userRfidNumber, String bookRfidNumber) {
        OkHttpClient client = new OkHttpClient();
        Gson gson = new Gson();

        // JSON 객체 생성
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("userRfidNumber", userRfidNumber);
        jsonObject.addProperty("bookRfidNum", bookRfidNumber);

        String jsonString = gson.toJson(jsonObject);

        // RequestBody 생성
        RequestBody body = RequestBody.create(jsonString, MediaType.get("application/json; charset=utf-8"));
        Log.d("sendLendingRequest", "Request body: " + jsonString);
        // Request 생성
        Request request = new Request.Builder()
                .url("http://52.79.204.104:8080/book/lend")
                .post(body)
                .build();

        // 로그 출력
        Log.d("sendLendingRequest", ": " + request);


        // Request 전송
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                getActivity().runOnUiThread(() -> Toast.makeText(getActivity(), "책 대출 실패", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }
                getActivity().runOnUiThread(() -> Toast.makeText(getActivity(), "책 대출 성공", Toast.LENGTH_SHORT).show());
            }
        });
    }
    private boolean isHexadecimal(String str) {
        return str.matches("\\p{XDigit}+");
    }
    private void receiveAuthenticationData(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            int length = data.length;
            int offset = 0;

            while (offset < length) {
                int chunkSize = Math.min(5, length - offset); // 5바이트만큼 가져옴
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(data, offset, chunk, 0, chunkSize);
                String hexString = TextUtil.toHexString(chunk); // 바이트 배열을 헥스 문자열로 변환

                // 수신된 데이터가 16진수인지 확인하고 hexEnabled 플래그 설정
                hexEnabled = isHexadecimal(hexString);

                // 띄어쓰기 제거
                hexString = hexString.replace(" ", "");
                // 현재 작업 유형에 따라 데이터 필터링
                if (hexString.length() == 10 && hexString.startsWith("65")) {
                    if (receivedDataSet.add(hexString)) { // 중복되지 않은 데이터만 추가
                        receivedDataList.add(hexString);
                        userRfidNumber = hexString; // 회원 인증 데이터 저장
                        Toast.makeText(getActivity(), "회원인증 되었습니다.", Toast.LENGTH_SHORT).show();
                        spn.append(hexString).append('\n');
                    }
                }

                offset += chunkSize; // 다음 청크로 이동
            }
        }
        updateReceiveTextView(spn);
    }

    private void receiveBorrowingData(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            int length = data.length;
            int offset = 0;

            while (offset < length) {
                int chunkSize = Math.min(12, length - offset); // 12바이트만큼 가져옴
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(data, offset, chunk, 0, chunkSize);
                String hexString = TextUtil.toHexString(chunk); // 바이트 배열을 헥스 문자열로 변환

                // 수신된 데이터가 16진수인지 확인하고 hexEnabled 플래그 설정
                hexEnabled = isHexadecimal(hexString);

                // 띄어쓰기 제거
                hexString = hexString.replace(" ", "");

                // 12바이트이고 E2로 시작하는 데이터만 처리
                if ( hexString.length() == 24 && hexString.startsWith("E2")) {
                    if (receivedDataSet.add(hexString)) { // 중복되지 않은 데이터만 추가
                        receivedDataList.add(hexString);
                        bookRfidNumbers.add(hexString); // 책 대출 데이터 리스트에 추가
                        spn.append(hexString).append('\n');
                    }
                }

                offset += chunkSize; // 다음 청크로 이동
            }
        }
        updateReceiveTextView(spn);
    }



    // 버튼 클릭 시 호출될 메서드들
    private void showBookLocation() {
        String currentUrl = webView.getUrl(); // 현재 WebView의 URL 가져오기
        location = extractCallNumber(currentUrl); // URL에서 호출 번호 추출
        if (location != null) {
            locationNumberTextView.setText(location); // 호출 번호가 있다면 TextView에 표시
        } else {
            locationNumberTextView.setText("책 위치가 확인되지 않았습니다."); // 호출 번호가 없다면 해당 내용을 TextView에 표시
        }
        Toast.makeText(getActivity(), "책 위치 표시", Toast.LENGTH_SHORT).show(); // 사용자에게 메시지 표시
        bluetoothConnectionAllowed = true;
        connect();
        // 일정 시간 지연 후 데이터 전송
        new Handler().postDelayed(() -> {
            if (connected == Connected.True) {
                send(locationNumberTextView.getText().toString());
                disconnect();
            } else {
                Toast.makeText(getActivity(), "Bluetooth connection failed", Toast.LENGTH_SHORT).show();
            }
        }, 3000);

    }

    // URL에서 호출 번호 추출하는 메서드
    private String extractCallNumber(String url) {
        String xValue = extractValue(url, "x");
        String yValue = extractValue(url, "y");

        if (xValue != null && yValue != null) {
            return xValue + yValue;
        } else {
            return null;
        }
    }

    private String extractValue(String url, String param) {
        String pattern = param + "=([^&]*)";
        Pattern r = Pattern.compile(pattern);
        Matcher m = r.matcher(url);
        if (m.find()) {
            return m.group(1);
        } else {
            return null;
        }
    }

    // 회원인증
    private void authenticateUser() {
        if (isAuthenticating) {
            return; // 이미 인증 중인 경우 무시
        }
        isAuthenticating = true;
        currentOperation = OperationType.AUTHENTICATION;
        Toast.makeText(getActivity(), "회원 인증을 해주세요", Toast.LENGTH_SHORT).show();

        bluetoothConnectionAllowed = true;
        connect();

        new Handler().postDelayed(() -> {
            if (isAuthenticating) {
                bluetoothConnectionAllowed = false;
                isAuthenticating = false;
                currentOperation = OperationType.NONE;
                disconnect();


            }
        }, BLUETOOTH_CONNECTION_DELAY);
    }

    //책 스캔
    private void scanBook() {
        if (userRfidNumber == null) {
            Toast.makeText(getActivity(), "먼저 회원인증을 해주세요", Toast.LENGTH_SHORT).show();
            return;
        }
        // 데이터 초기화
        bookRfidNumbers.clear();
        receivedDataList.clear();
        receivedDataSet.clear();
        currentOperation = OperationType.BORROWING;
        bluetoothConnectionAllowed = true;
        connect();

        new Handler().postDelayed(() -> {
            bluetoothConnectionAllowed = false;
            currentOperation = OperationType.NONE;
            disconnect();
            Log.d("scanBook", "블루투스 통신 완료, 다이얼로그 표시");

            showReceivedBookListDialog(); // 수신된 책 리스트 다이얼로그 표시
        }, BLUETOOTH_CONNECTION_DELAY);
    }
    // 수신된 책 RFID 번호를 리스트로 보여줄 다이얼로그 메서드
    private void showReceivedBookListDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

        LayoutInflater inflater = getActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_book, null);
        builder.setView(dialogView);

        ListView listView = dialogView.findViewById(R.id.list_item);
        Button borrowButton = dialogView.findViewById(R.id.btn_bollow);

        List<BookItem> bookItems = new ArrayList<>();
        for (String rfid : bookRfidNumbers) {
            String title;
            switch (rfid) {
                case "E200470A6B406821AFCA010D":
                    title = "나미야 잡화점의 기적";
                    break;
                case "E2000017570D01460540DF27":
                    title = "불편한 편의점2";
                    break;
                case "E200470D3DD0682188260113":
                    title = "사서함 110호의 우편물";
                    break;
                default:
                    title = "알 수 없는 책";
                    break;
            }
            bookItems.add(new BookItem(title, rfid));
        }

        BookListAdapter adapter = new BookListAdapter(getActivity(), R.layout.booklist, bookItems);
        listView.setAdapter(adapter);

        builder.setPositiveButton("닫기", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();

        borrowButton.setOnClickListener(v -> {
            List<String> selectedRfids = adapter.getSelectedRfids();
            if (selectedRfids.isEmpty()) {
                Toast.makeText(getActivity(), "대출할 책을 선택해주세요", Toast.LENGTH_SHORT).show();
            } else {
                for (String rfid : selectedRfids) {
                    sendLendingRequest(userRfidNumber, rfid);
                }
            }
        });
    }
    // 수신된 데이터를 텍스트 뷰에 업데이트하는 메서드
    private void updateReceiveTextView(SpannableStringBuilder spn) {
        receiveText.append(spn);
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn); // 상태 메시지를 텍스트 뷰에 추가
    }

    // SerialListener 인터페이스 메서드 구현
    @Override
    public void onSerialConnect() {
        status("connected"); // 연결 성공 상태 표시
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage()); // 연결 실패 메시지 표시
        disconnect(); // 연결 해제
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        if (currentOperation == OperationType.AUTHENTICATION) {
            receiveAuthenticationData(datas); // 데이터 수신 (회원 인증)
        } else if (currentOperation == OperationType.BORROWING) {
            receiveBorrowingData(datas); // 데이터 수신 (책 대출)
        }
    }

    @Override
    public void onSerialRead(ArrayDeque<byte[]> datas) {
        if (currentOperation == OperationType.AUTHENTICATION) {
            receiveAuthenticationData(datas); // 데이터 수신 (회원 인증)
        } else if (currentOperation == OperationType.BORROWING) {
            receiveBorrowingData(datas); // 데이터 수신 (책 대출)
        }
    }
    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage()); // 입출력 오류 메시지 표시
        disconnect(); // 연결 해제
    }
}
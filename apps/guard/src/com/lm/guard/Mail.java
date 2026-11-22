package com.lm.guard;

import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/* loaded from: classes.dex */
public class Mail {
    static final int CONNECT_MS = 15000;
    static final int READ_MS = 20000;

    public static class Result {
        public String msg;
        public boolean ok;

        Result(boolean z, String str) {
            this.ok = z;
            this.msg = str;
        }
    }

    static SSLSocketFactory sslFactory() {
        try {
            SSLContext sSLContext = SSLContext.getInstance("TLS");
            sSLContext.init(null, new TrustManager[]{new X509TrustManager() { // from class: com.lm.guard.Mail.1
                @Override // javax.net.ssl.X509TrustManager
                public void checkClientTrusted(X509Certificate[] x509CertificateArr, String str) {
                }

                @Override // javax.net.ssl.X509TrustManager
                public void checkServerTrusted(X509Certificate[] x509CertificateArr, String str) {
                }

                @Override // javax.net.ssl.X509TrustManager
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return sSLContext.getSocketFactory();
        } catch (Throwable th) {
            Log.w("GUARD", "sslFactory: " + th);
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    static void enableTls(SSLSocket sSLSocket) {
        try {
            ArrayList arrayList = new ArrayList();
            for (String str : sSLSocket.getSupportedProtocols()) {
                if ("TLSv1.2".equalsIgnoreCase(str) || "TLSv1.1".equalsIgnoreCase(str) || "TLSv1".equalsIgnoreCase(str)) {
                    arrayList.add(str);
                }
            }
            if (!arrayList.isEmpty()) {
                sSLSocket.setEnabledProtocols((String[]) arrayList.toArray(new String[0]));
            }
            try {
                ArrayList arrayList2 = new ArrayList();
                for (String str2 : sSLSocket.getSupportedCipherSuites()) {
                    if (str2.contains("ECDHE") || str2.contains("DHE_RSA") || str2.contains("AES_128_GCM")) {
                        arrayList2.add(str2);
                    }
                }
                if (!arrayList2.isEmpty()) {
                    sSLSocket.setEnabledCipherSuites((String[]) arrayList2.toArray(new String[0]));
                }
            } catch (Throwable th) {
            }
        } catch (Throwable th2) {
            Log.w("GUARD", "enableTls: " + th2);
        }
    }

    public static Result send(Prefs prefs, String str, String str2, byte[] bArr, String str3) {
        Socket createSocket;
        Socket socket = null;
        try {
            if (prefs.ssl) {
                SSLSocketFactory sslFactory = sslFactory();
                Socket createSocket2 = sslFactory.createSocket();
                createSocket2.connect(new InetSocketAddress(prefs.host, prefs.port), CONNECT_MS);
                createSocket2.setSoTimeout(READ_MS);
                createSocket = sslFactory.createSocket(createSocket2, prefs.host, prefs.port, true);
                enableTls((SSLSocket) createSocket);
                ((SSLSocket) createSocket).startHandshake();
            } else {
                Socket socket2 = new Socket();
                try {
                    socket2.connect(new InetSocketAddress(prefs.host, prefs.port), CONNECT_MS);
                    socket2.setSoTimeout(READ_MS);
                    createSocket = socket2;
                } catch (Throwable th) {
                    th = th;
                    socket = socket2;
                    try {
                        Log.w("GUARD", "mail err", th);
                        Result result = new Result(false, errName(th));
                        if (socket != null) {
                            try {
                                socket.close();
                            } catch (Throwable th2) {
                            }
                        }
                        return result;
                    } finally {
                    }
                }
            }
            Smtp smtp = new Smtp(createSocket);
            String read = smtp.read();
            if (!code(read, '2')) {
                Result result2 = new Result(false, "连接被拒: " + one(read));
                if (createSocket != null) {
                    try {
                        createSocket.close();
                    } catch (Throwable th3) {
                    }
                }
                return result2;
            }
            String cmd = smtp.cmd("EHLO t70l");
            if (!code(cmd, '2')) {
                Result result3 = new Result(false, "EHLO 失败: " + one(cmd));
                if (createSocket != null) {
                    try {
                        createSocket.close();
                    } catch (Throwable th4) {
                    }
                }
                return result3;
            }
            if (!prefs.ssl && cmd.toUpperCase(Locale.US).contains("STARTTLS") && code(smtp.cmd("STARTTLS"), '2')) {
                SSLSocket sSLSocket = (SSLSocket) sslFactory().createSocket(smtp.sock, prefs.host, prefs.port, true);
                enableTls(sSLSocket);
                sSLSocket.startHandshake();
                smtp.replace(sSLSocket);
                smtp.cmd("EHLO t70l");
            }
            String cmd2 = smtp.cmd("AUTH LOGIN");
            if (!code(cmd2, '3')) {
                Result result4 = new Result(false, "服务器不支持 AUTH LOGIN: " + one(cmd2));
                if (createSocket != null) {
                    try {
                        createSocket.close();
                    } catch (Throwable th5) {
                    }
                }
                return result4;
            }
            String cmd3 = smtp.cmd(Base64.encodeToString(prefs.user.getBytes("UTF-8"), 2));
            if (!code(cmd3, '3')) {
                Result result5 = new Result(false, "账号被拒: " + one(cmd3));
                if (createSocket != null) {
                    try {
                        createSocket.close();
                    } catch (Throwable th6) {
                    }
                }
                return result5;
            }
            String cmd4 = smtp.cmd(Base64.encodeToString(prefs.pass.getBytes("UTF-8"), 2));
            if (!code(cmd4, '2')) {
                Result result6 = new Result(false, "密码/授权码被拒: " + one(cmd4));
                if (createSocket != null) {
                    try {
                        createSocket.close();
                    } catch (Throwable th7) {
                    }
                }
                return result6;
            }
            String cmd5 = smtp.cmd("MAIL FROM:<" + prefs.user + ">");
            if (!code(cmd5, '2')) {
                Result result7 = new Result(false, "MAIL FROM 失败: " + one(cmd5));
                if (createSocket != null) {
                    try {
                        createSocket.close();
                    } catch (Throwable th8) {
                    }
                }
                return result7;
            }
            String cmd6 = smtp.cmd("RCPT TO:<" + prefs.to + ">");
            if (!code(cmd6, '2')) {
                Result result8 = new Result(false, "收件人被拒: " + one(cmd6));
                if (createSocket != null) {
                    try {
                        createSocket.close();
                    } catch (Throwable th9) {
                    }
                }
                return result8;
            }
            String cmd7 = smtp.cmd("DATA");
            if (!code(cmd7, '3')) {
                Result result9 = new Result(false, "DATA 被拒: " + one(cmd7));
                if (createSocket != null) {
                    try {
                        createSocket.close();
                    } catch (Throwable th10) {
                    }
                }
                return result9;
            }
            smtp.write(buildMime(prefs, str, str2, bArr, str3));
            String read2 = smtp.read();
            try {
                smtp.cmd("QUIT");
            } catch (Throwable th11) {
            }
            if (code(read2, '2')) {
                Result result10 = new Result(true, "已发送");
                if (createSocket != null) {
                    try {
                        createSocket.close();
                    } catch (Throwable th12) {
                    }
                }
                return result10;
            }
            Result result11 = new Result(false, "投递失败: " + one(read2));
            if (createSocket != null) {
                try {
                    createSocket.close();
                } catch (Throwable th13) {
                }
            }
            return result11;
        } catch (Throwable th14) {
            // 反编译残留：原版这里是统一兜底异常，返回失败结果
            return new Result(false, errName(th14));
        }
    }

    static String errName(Throwable th) {
        String str = th.getClass().getSimpleName() + ": " + th.getMessage();
        if (th instanceof SocketTimeoutException) {
            str = "连接超时";
        } else if (th instanceof UnknownHostException) {
            str = "域名解析失败";
        } else if (th instanceof SSLHandshakeException) {
            str = "加密握手失败（端口不对？465 要勾 SSL）";
        } else if (th instanceof SSLException) {
            str = "SSL 握手失败（端口/加密方式不对）";
        } else if (th instanceof ConnectException) {
            str = "连不上服务器（端口被挡？）";
        }
        return str.length() > 90 ? str.substring(0, 90) : str;
    }

    static boolean code(String str, char c) {
        return str != null && str.length() > 0 && str.charAt(0) == c;
    }

    static String one(String str) {
        if (str == null) {
            return "";
        }
        int indexOf = str.indexOf(10);
        if (indexOf > 0) {
            str = str.substring(0, indexOf);
        }
        if (str.length() <= 70) {
            return str.replace("\r", "");
        }
        return str.substring(0, 70);
    }

    static String buildMime(Prefs prefs, String str, String str2, byte[] bArr, String str3) throws IOException {
        String str4 = "==T70LGUARD_" + System.currentTimeMillis();
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("From: =?UTF-8?B?").append(Base64.encodeToString("T70L看家".getBytes("UTF-8"), 2)).append("?= <").append(prefs.user).append(">\r\n");
        sb.append("To: <").append(prefs.to).append(">\r\n");
        sb.append("Subject: =?UTF-8?B?").append(Base64.encodeToString(str.getBytes("UTF-8"), 2)).append("?=\r\n");
        sb.append("Date: ").append(simpleDateFormat.format(new Date())).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(str4).append("\"\r\n");
        sb.append("\r\n");
        sb.append("--").append(str4).append("\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(chunk(Base64.encodeToString(str2.getBytes("UTF-8"), 2))).append("\r\n");
        if (bArr != null && bArr.length > 0) {
            sb.append("--").append(str4).append("\r\n");
            sb.append("Content-Type: image/jpeg; name=\"").append(str3).append("\"\r\n");
            sb.append("Content-Transfer-Encoding: base64\r\n");
            sb.append("Content-Disposition: attachment; filename=\"").append(str3).append("\"\r\n\r\n");
            sb.append(chunk(Base64.encodeToString(bArr, 2))).append("\r\n");
        }
        sb.append("--").append(str4).append("--\r\n");
        sb.append(".\r\n");
        return sb.toString();
    }

    static String chunk(String str) {
        StringBuilder sb = new StringBuilder(str.length() + (str.length() / 60));
        int i = 0;
        while (i < str.length()) {
            int i2 = i + 76;
            sb.append((CharSequence) str, i, Math.min(str.length(), i2)).append("\r\n");
            i = i2;
        }
        return sb.toString();
    }

    static class Smtp {
        InputStream in;
        OutputStream out;
        Socket sock;

        Smtp(Socket socket) throws IOException {
            replace(socket);
        }

        void replace(Socket socket) throws IOException {
            this.sock = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        void write(String str) throws IOException {
            this.out.write(str.getBytes("UTF-8"));
            this.out.flush();
        }

        String cmd(String str) throws IOException {
            write(str + "\r\n");
            return read();
        }

        String read() throws IOException {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            StringBuilder sb = new StringBuilder();
            while (true) {
                int read = this.in.read();
                if (read < 0) {
                    break;
                }
                byteArrayOutputStream.write(read);
                if (read == 10) {
                    String str = new String(byteArrayOutputStream.toByteArray(), "ISO-8859-1");
                    byteArrayOutputStream.reset();
                    sb.append(str);
                    if (str.length() >= 4) {
                        if (str.charAt(3) == ' ') {
                            break;
                        }
                    }
                    if (str.length() > 0) {
                        if (str.charAt(0) == '4' && str.length() < 4) {
                            break;
                        }
                    }
                    if (str.length() > 0) {
                        if (str.charAt(0) == '5' && str.length() < 4) {
                            break;
                        }
                    }
                    if (str.length() > 0 && str.trim().matches("\\d{3}$")) {
                        break;
                    }
                }
            }
            return sb.toString();
        }
    }
}

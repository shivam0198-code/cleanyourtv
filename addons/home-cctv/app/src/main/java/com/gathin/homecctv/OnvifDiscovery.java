package com.gathin.homecctv;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OnvifDiscovery {
    private static final String TAG = "HomeCCTV_Onvif";

    private static final String PROBE_MSG =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<Envelope xmlns=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">\n" +
            "  <Header>\n" +
            "    <wsa:MessageID xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">uuid:c43b31c4-1234-5678-9abc-0123456789ab</wsa:MessageID>\n" +
            "    <wsa:To xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>\n" +
            "    <wsa:Action xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>\n" +
            "  </Header>\n" +
            "  <Body>\n" +
            "    <Probe xmlns=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">\n" +
            "      <Types>dn:NetworkVideoTransmitter</Types>\n" +
            "    </Probe>\n" +
            "  </Body>\n" +
            "</Envelope>";

    public interface DiscoveryCallback {
        void onDiscovered(String newIp);
    }

    public static void discoverAsync(DiscoveryCallback callback) {
        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                socket.setSoTimeout(2500);

                byte[] data = PROBE_MSG.getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data, data.length, InetAddress.getByName("239.255.255.250"), 3702);
                socket.send(packet);

                byte[] buf = new byte[4096];
                DatagramPacket recv = new DatagramPacket(buf, buf.length);
                socket.receive(recv);

                String response = new String(recv.getData(), 0, recv.getLength());
                socket.close();

                // Look for http://<ip>/onvif/device_service or similar
                Pattern p = Pattern.compile("http://([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+)");
                Matcher m = p.matcher(response);
                if (m.find()) {
                    String discoveredIp = m.group(1);
                    Log.i(TAG, "ONVIF Discovered Camera IP: " + discoveredIp);
                    if (callback != null) {
                        callback.onDiscovered(discoveredIp);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "ONVIF Discovery probe: " + e.getMessage());
            }
        }).start();
    }
}

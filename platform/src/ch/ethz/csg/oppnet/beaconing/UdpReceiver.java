
package ch.ethz.csg.oppnet.beaconing;

import android.util.Log;

import com.google.common.base.Optional;

import ch.ethz.csg.oppnet.beaconing.BeaconParser.PossibleBeacon;
import ch.ethz.csg.oppnet.beaconing.BeaconingManager.SocketType;
import ch.ethz.csg.oppnet.network.WifiConnection;
import ch.ethz.csg.oppnet.utils.InterruptibleFailsafeRunnable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

public abstract class UdpReceiver extends InterruptibleFailsafeRunnable {
    public static final String TAG = "WifiReceiver";

    private final BeaconingManager mBM;

    private final SocketType mSocketType;
    private final DatagramSocket mSocket;

    public UdpReceiver(BeaconingManager context, SocketType socketType) throws IOException {
        super(TAG);
        mBM = context;
        mSocketType = socketType;
        mSocket = createSocket();
    }

    protected abstract DatagramSocket createSocket() throws IOException;

    private boolean isOwnPacket(DatagramPacket packet, WifiConnection connection) {
        final InetAddress senderAddress = packet.getAddress();
        if (senderAddress instanceof Inet4Address && connection.hasIp4Address()) {
            return senderAddress.equals(connection.getIp4Address().get());
        } else if (senderAddress instanceof Inet6Address && connection.hasIp6Address()) {
            return senderAddress.equals(connection.getIp6Address().get());
        }
        return false;
    }

    @Override
    protected void execute() {
        Log.v(TAG, "UDPReceiver.execute: Received beacon");
        // Start receiving beacons
        byte[] buffer = new byte[BeaconingManager.RECEIVER_BUFFER_SIZE];
        final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!mThread.isInterrupted()) {
            // Android sometimes limits the incoming packet size to the previously received
            // packet size. The following call circumvents this problem.
            packet.setData(buffer);

            final WifiConnection wifiConnection;
            try {
                mSocket.receive(packet);
                Optional<WifiConnection> conn = mBM.mNetManager.getCurrentConnection();
                if (!conn.isPresent()) {
                    // The wifi is disconnected, stop listening
                    break;
                }
                wifiConnection = conn.get();
            } catch (SocketTimeoutException e) {
                // No beacon received, try again
                continue;
            } catch (IOException e) {
                Log.e(TAG, "Error while receiving beacon, aborting.", e);
                break;
            }
            final long timeReceived = System.currentTimeMillis() / 1000;

            // Skip if packet is empty, from ourselves or real mDNS
            if (packet.getLength() == 0 || isOwnPacket(packet, wifiConnection)) {
                continue;
            }

            // Decode packet
            final PossibleBeacon possibleBeacon = PossibleBeacon.from(
                    packet, timeReceived, wifiConnection.getNetworkName().get(),
                    mSocketType, mBM.mMasterIdentity);

            mBM.onBeaconReceived(possibleBeacon);
        }

        // destroy socket
        mSocket.close();
    }

    // IMPLEMENTATIONS
    public static class UdpUnicastReceiver extends UdpReceiver {
        public UdpUnicastReceiver(BeaconingManager context) throws IOException {
            super(context, SocketType.UNICAST);
        }

        @Override
        protected DatagramSocket createSocket() throws IOException {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setSoTimeout(BeaconingManager.RECEIVER_SOCKET_TIMEOUT);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(BeaconingManager.RECEIVER_PORT_UNICAST));
            return socket;
        }
    }
}


package ch.ethz.csg.oppnet.beaconing;

import android.util.Log;

import ch.ethz.csg.oppnet.lib.data.Neighbor;
import ch.ethz.csg.oppnet.network.NetworkManager.WifiState;
import ch.ethz.csg.oppnet.network.WifiConnection;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos;
import ch.ethz.csg.oppnet.utils.InterruptibleFailsafeRunnable;

import com.google.common.base.Optional;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class UdpSender extends InterruptibleFailsafeRunnable {
    public static final String TAG = "WifiSender";

    private final BeaconingManager mBM;
    private final boolean mPerformSubnetSweep;
    private final int mBurstSize;
    private final byte mApLikelihood;

    private final InetAddress mReplyTo;
    private final OppNetProtos.Beacon mReceivedBeacon;

    public UdpSender(BeaconingManager context, InetAddress replyTo,
            OppNetProtos.Beacon receivedBeacon, int apLikelihood) {

        super(TAG);
        mBM = context;
        mPerformSubnetSweep = false;
        mBurstSize = 1;
        mApLikelihood = (byte) (apLikelihood & 0xFF);

        mReplyTo = replyTo;
        mReceivedBeacon = receivedBeacon;
    }

    public UdpSender(BeaconingManager context, boolean subnetSweep, int burstSize, int apLikelihood) {
        super(TAG);
        mBM = context;
        mPerformSubnetSweep = subnetSweep;
        mBurstSize = burstSize;
        mApLikelihood = (byte) (apLikelihood & 0xFF);

        mReplyTo = null;
        mReceivedBeacon = null;
    }

    @Override
    public void execute() {
        Log.v(TAG, "Udpsender.execute");
        final WifiState wifiState = mBM.mNetManager.getWifiState();
        final Optional<WifiConnection> wifiConnection = mBM.mNetManager.getCurrentConnection();
        if (wifiState.equals(WifiState.DISCONNECTED) || !wifiConnection.isPresent()) {
            return;
        }

        final WifiConnection connection = wifiConnection.get();

        // Build receiver list
        final List<InetSocketAddress> receivers = new ArrayList<>();
        final Set<Neighbor> neighbors =
                mBM.mDbController.getNeighbors(BeaconingManager.getCurrentTimestamp());

        if (mReplyTo != null) {
            receivers.add(new InetSocketAddress(
                    mReplyTo, BeaconingManager.RECEIVER_PORT_UNICAST));
        } else {
            switch (wifiState) {
                case OPPNET_AP: {
                    //TODO: Create a registry of IP addresses that will be sent out
                    // to any connecting station
                    // Send beacon to all neighbors (using unicast)
                    addNeighborsAsUnicastTargets(receivers, neighbors);
                    Log.v(TAG, "OPPNET_AP");
                    break;
                }
                case STA_ON_OPPNET_AP: {
                    // Send beacon to access point node (using unicast), but nobody else
                    receivers.add(new InetSocketAddress(
                            connection.getApAddress().get(),
                            BeaconingManager.RECEIVER_PORT_UNICAST));
                    Log.v(TAG, "STA_ON_OPPNET_AP");
                    break;
                }
                case STA_ON_PUBLIC_AP: {
                    // Send beacon to all neighbors (using unicast)
                    addNeighborsAsUnicastTargets(receivers, neighbors);
                    Log.v(TAG, "STA_ON_PUBLIC_AP");
                    if (mPerformSubnetSweep) {
                        Log.v(TAG, "Sweeping Unicast target in subnet");
                        addUnicastSweepTargets(receivers);
                    }
                    break;
                }
                default: {
                    Log.v(TAG, "No receivers?");
                    // No receivers!
                    return;
                }
            }
        }
        if (receivers.isEmpty()) {
            return;
        }

        // Build beacon
        final Set<ByteBuffer> protocols =
                mBM.mProtocolRegistry.getAllProtocolImplementations().keySet();

        final byte[] beaconData;
        if (mReplyTo != null) {
            beaconData = mBM.mBeaconBuilder.buildReply(
                    wifiState, wifiConnection, protocols, neighbors, mReceivedBeacon);
        } else if (wifiState.equals(WifiState.STA_ON_OPPNET_AP)) {
            beaconData = mBM.mBeaconBuilder.buildBeacon(
                    wifiState, wifiConnection, protocols, mApLikelihood);
        } else {
            beaconData = mBM.mBeaconBuilder.buildBeacon(
                    wifiState, wifiConnection, protocols, neighbors);
        }

        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
        } catch (SocketException e) {
            Log.e(TAG, "Could not create socket for beacon sending");
            e.printStackTrace();
        }


        final DatagramPacket packet = new DatagramPacket(beaconData, beaconData.length);
        for (InetSocketAddress receiver : receivers) {
            Log.v(TAG, "Unicast datagram send to" + receiver);
            if (mThread.isInterrupted()) {
                break;
            }

            packet.setSocketAddress(receiver);

            try {
                socket.send(packet);
            } catch (IOException e) {
                Log.e(TAG, "Could not create socket to send beacon to " + receiver);
                e.printStackTrace();
            }
        }

        Log.v(TAG, String.format(
                "Sent %d beacons (size: %d bytes) to %d receivers",
                mBurstSize, beaconData.length, receivers.size()));
    }

    private void addNeighborsAsUnicastTargets(
            List<InetSocketAddress> receivers, Set<Neighbor> neighbors) {

        for (Neighbor neighbor : neighbors) {
            if (neighbor.hasAnyIpAddress()) {
                receivers.add(new InetSocketAddress(
                        neighbor.getAnyIpAddress(), BeaconingManager.RECEIVER_PORT_UNICAST));
            }
        }
    }

    private void addUnicastSweepTargets(List<InetSocketAddress> receivers) {
        for (InetAddress addr : mBM.mNetManager.getIp4SweepRange()) {
            receivers.add(new InetSocketAddress(addr, BeaconingManager.RECEIVER_PORT_UNICAST));
        }
    }

}

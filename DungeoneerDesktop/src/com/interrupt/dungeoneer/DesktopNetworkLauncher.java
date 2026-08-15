package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.multiplayer.network.DirectConnectCompatibility;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectDiagnosticReport;
import com.interrupt.dungeoneer.multiplayer.network.DirectConnectDiagnostics;
import com.interrupt.dungeoneer.multiplayer.network.LanPrivateSession;
import com.interrupt.dungeoneer.multiplayer.network.OpenSourceTestCompatibility;
import com.interrupt.dungeoneer.multiplayer.network.PrivateSessionDiscovery;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Console surface for discovery, reachability, and manual setup guidance. */
final class DesktopNetworkLauncher {
    private DesktopNetworkLauncher() { }

    static void run(DesktopLaunchOptions options, PrintStream output) throws IOException {
        if(options == null) throw new IllegalArgumentException("Launch options cannot be null.");
        if(output == null) throw new IllegalArgumentException("Network output cannot be null.");

        if(options.discoverPrivateSessions) {
            DirectConnectCompatibility compatibility = loadPackagedCompatibility();
            List<LanPrivateSession> sessions = PrivateSessionDiscovery.discover(
                    options.sessionPort, compatibility);
            output.println("Compatible LAN Private Sessions on UDP port "
                    + options.sessionPort + ":");
            if(sessions.isEmpty()) {
                output.println("  None found. Confirm Host is running, port matches, and Windows Private-network firewall permission is allowed.");
            }
            else {
                for(LanPrivateSession session : sessions) {
                    output.println("  " + session.getCampaignId() + " at "
                            + session.getEndpoint() + " | " + session.getClaimedSlots()
                            + "/" + session.getCapacity() + " Campaign Slots claimed | "
                            + (session.isLobbyOpen() ? "Lobby open" : "Active Floor; no hot-join")
                            + " | Session " + session.getSessionId());
                }
            }
            output.println("Discovery sent bounded UDP probes only; no firewall or router settings were changed.");
        }

        if(options.diagnoseDirectConnectAddress != null) {
            DirectConnectCompatibility compatibility = loadPackagedCompatibility();
            DirectConnectDiagnosticReport report = DirectConnectDiagnostics.diagnose(
                    options.diagnoseDirectConnectAddress, options.sessionPort,
                    compatibility);
            output.println("Direct Connect diagnostics for " + report.getEndpoint() + ":");
            output.println("  TCP: " + label(report.isTcpReachable()) + " - "
                    + report.getTcpDetail());
            output.println("  UDP: " + label(report.isUdpReachable()) + " - "
                    + report.getUdpDetail());
            output.println(DirectConnectGuidance.forPort(options.sessionPort));
        }
        else if(options.networkHelp) {
            output.println(DirectConnectGuidance.forPort(options.sessionPort));
        }
    }

    private static String label(boolean reachable) {
        return reachable ? "REACHABLE" : "NO RESPONSE";
    }

    private static DirectConnectCompatibility loadPackagedCompatibility()
            throws IOException {
        if(DesktopNetworkLauncher.class.getClassLoader()
                .getResource("packaged_files.txt") != null) {
            return loadClasspathCompatibility();
        }
        File assetRoot = new File(".");
        if(new File(assetRoot, "packaged_files.txt").isFile()) {
            return loadCompatibility(assetRoot);
        }
        throw new FileNotFoundException(
                "packaged_files.txt was not found in working directory or application package.");
    }

    static DirectConnectCompatibility loadCompatibility(final File assetRoot)
            throws IOException {
        if(assetRoot == null || !assetRoot.isDirectory()) {
            throw new IOException("Open-source asset directory is unavailable: " + assetRoot);
        }
        final File canonicalRoot = assetRoot.getCanonicalFile();
        String index = new String(Files.readAllBytes(
                new File(canonicalRoot, "packaged_files.txt").toPath()),
                StandardCharsets.UTF_8);
        return OpenSourceTestCompatibility.fromPackagedIndex(index,
                new OpenSourceTestCompatibility.AssetSource() {
                    @Override
                    public boolean exists(String path) {
                        try {
                            return resolve(canonicalRoot, path).isFile();
                        }
                        catch(IOException ex) {
                            return false;
                        }
                    }

                    @Override
                    public byte[] read(String path) throws IOException {
                        return Files.readAllBytes(resolve(canonicalRoot, path).toPath());
                    }
                });
    }

    private static DirectConnectCompatibility loadClasspathCompatibility()
            throws IOException {
        final ClassLoader loader = DesktopNetworkLauncher.class.getClassLoader();
        InputStream indexStream = loader.getResourceAsStream("packaged_files.txt");
        if(indexStream == null) {
            throw new FileNotFoundException(
                    "packaged_files.txt was not found in working directory or application package.");
        }
        String index;
        try {
            index = new String(readFully(indexStream), StandardCharsets.UTF_8);
        }
        finally {
            indexStream.close();
        }
        return OpenSourceTestCompatibility.fromPackagedIndex(index,
                new OpenSourceTestCompatibility.AssetSource() {
                    @Override
                    public boolean exists(String path) {
                        return loader.getResource(path) != null;
                    }

                    @Override
                    public byte[] read(String path) throws IOException {
                        InputStream stream = loader.getResourceAsStream(path);
                        if(stream == null) throw new FileNotFoundException(path);
                        try {
                            return readFully(stream);
                        }
                        finally {
                            stream.close();
                        }
                    }
                });
    }

    private static File resolve(File root, String path) throws IOException {
        File candidate = new File(root, path).getCanonicalFile();
        String rootPath = root.getPath();
        if(!candidate.getPath().startsWith(rootPath + File.separator)) {
            throw new IOException("Open-source asset path escaped asset directory: " + path);
        }
        return candidate;
    }

    private static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while((read = stream.read(buffer)) >= 0) {
            if(read > 0) output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}

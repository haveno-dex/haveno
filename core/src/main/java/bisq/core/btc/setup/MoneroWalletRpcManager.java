package bisq.core.btc.setup;

import java.net.ServerSocket;

import java.io.IOException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import monero.common.MoneroError;
import monero.wallet.MoneroWalletRpc;

/**
 * Manages monero-wallet-rpc processes bound to ports.
 */
@Slf4j
public class MoneroWalletRpcManager {

  private static final String RPC_BIND_PORT_ARGUMENT = "--rpc-bind-port";
  private static int NUM_ALLOWED_ATTEMPTS = 2; // allow this many attempts to bind to an assigned port
  private Integer startPort;
  private final Map<Integer, MoneroWalletRpc> registeredPorts = new HashMap<>();

  /**
   * Manage monero-wallet-rpc instances by auto-assigning ports.
   */
  public MoneroWalletRpcManager() { }

  /**
   * Manage monero-wallet-rpc instances by assigning consecutive ports from a starting port.
   *
   * @param startPort is the starting port to bind to
   */
  public MoneroWalletRpcManager(int startPort) {
    this.startPort = startPort;
  }

  /**
   * Start a new instance of monero-wallet-rpc.
   *
   * @param cmd command line parameters to start monero-wallet-rpc
   * @return a client connected to the monero-wallet-rpc instance
   */
  public MoneroWalletRpc startInstance(List<String> cmd) {

    try {

      // register given port
      if (cmd.contains(RPC_BIND_PORT_ARGUMENT)) {
        int portArgumentPosition = cmd.indexOf(RPC_BIND_PORT_ARGUMENT) + 1;
        int port = Integer.parseInt(cmd.get(portArgumentPosition));
        MoneroWalletRpc walletRpc = new MoneroWalletRpc(cmd); // starts monero-wallet-rpc process
        registeredPorts.put(port, walletRpc);
        return walletRpc;
      }

      // register assigned ports up to maximum attempts
      else {
        int numAttempts = 0;
        while (numAttempts < NUM_ALLOWED_ATTEMPTS) {
          int port = -1;
          try {
            numAttempts++;
            port = registerPort();
            List<String> cmdCopy = new ArrayList<>(cmd); // preserve original cmd
            cmdCopy.add(RPC_BIND_PORT_ARGUMENT);
            cmdCopy.add("" + port);
            MoneroWalletRpc walletRpc = new MoneroWalletRpc(cmdCopy); // start monero-wallet-rpc process
            registeredPorts.put(port, walletRpc);
            return walletRpc;
          } catch (Exception e) {
            if (numAttempts >= NUM_ALLOWED_ATTEMPTS) {
              log.error("Unable to start monero-wallet-rpc instance after {} attempts", NUM_ALLOWED_ATTEMPTS);
              throw e;
            }
          }
        }
        throw new MoneroError("Failed to start monero-wallet-rpc instance after " + NUM_ALLOWED_ATTEMPTS + " attempts"); // should never reach here
      }
    } catch (IOException e) {
      throw new MoneroError(e);
    }
  }

  /**
   * Stop an instance of monero-wallet-rpc.
   *
   * @param walletRpc the client connected to the monero-wallet-rpc instance to stop
   */
  public void stopInstance(MoneroWalletRpc walletRpc) {
    boolean found = false;
    for (Map.Entry<Integer, MoneroWalletRpc> entry : registeredPorts.entrySet()) {
      if (walletRpc == entry.getValue()) {
        walletRpc.stop();
        found = true;
        try { unregisterPort(entry.getKey()); }
        catch (Exception e) { throw new MoneroError(e); }
        break;
      }
    }
    if (!found) throw new RuntimeException("MoneroWalletRpc instance not associated with port");
  }

  private int registerPort() throws IOException {

    // register next consecutive port
    if (startPort != null) {
      int port = startPort;
      while (registeredPorts.containsKey(port)) port++;
      registeredPorts.put(port, null);
      return port;
    }

    // register auto-assigned port
    else {
      ServerSocket socket = new ServerSocket(0); // use socket to get available port
      int port = socket.getLocalPort();
      socket.close();
      registeredPorts.put(port, null);
      return port;
    }
  }

  private void unregisterPort(int port) {
    registeredPorts.remove(port);
  }
}

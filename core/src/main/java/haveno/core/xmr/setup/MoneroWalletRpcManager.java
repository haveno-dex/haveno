package haveno.core.xmr.setup;

import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroError;
import monero.wallet.MoneroWalletRpc;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
              synchronized (registeredPorts) {
                  if (registeredPorts.containsKey(port)) throw new RuntimeException("Port " + port + " is already registered");
                  registeredPorts.put(port, null);
              }
              MoneroWalletRpc walletRpc = new MoneroWalletRpc(cmd); // starts monero-wallet-rpc process
              synchronized (registeredPorts) {
                  registeredPorts.put(port, walletRpc);
              }
              return walletRpc;
          }

          // register assigned ports up to maximum attempts
          else {
              int numAttempts = 0;
              while (numAttempts < NUM_ALLOWED_ATTEMPTS) {
                  int port;
                  try {
                      numAttempts++;

                      // get port
                      if (startPort != null) port = registerNextPort();
                      else {
                        ServerSocket socket = new ServerSocket(0);
                        port = socket.getLocalPort();
                        socket.close();
                        synchronized (registeredPorts) {
                            registeredPorts.put(port, null);
                        }
                      }

                      // start monero-wallet-rpc
                      List<String> cmdCopy = new ArrayList<>(cmd); // preserve original cmd
                      cmdCopy.add(RPC_BIND_PORT_ARGUMENT);
                      cmdCopy.add("" + port);
                      MoneroWalletRpc walletRpc = new MoneroWalletRpc(cmdCopy); // start monero-wallet-rpc process
                      synchronized (registeredPorts) {
                          registeredPorts.put(port, walletRpc);
                      }
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

      // unregister port
      int port = -1;
      synchronized (registeredPorts) {
          boolean found = false;
          for (Map.Entry<Integer, MoneroWalletRpc> entry : registeredPorts.entrySet()) {
              if (walletRpc == entry.getValue()) {
                  found = true;
                  try {
                      port = entry.getKey();
                      unregisterPort(port);
                  } catch (Exception e) {
                      throw new MoneroError(e);
                  }
                  break;
              }
          }
          if (!found) throw new RuntimeException("MoneroWalletRpc instance not registered with a port");
      }

      // stop process
      String pid = walletRpc.getProcess() == null ? null : String.valueOf(walletRpc.getProcess().pid());
      log.info("Stopping MoneroWalletRpc path={}, port={}, pid={}", walletRpc.getPath(), port, pid);
      walletRpc.stopProcess();
  }

  private int registerNextPort() throws IOException {
      synchronized (registeredPorts) {
            int port = startPort;
            while (registeredPorts.containsKey(port)) port++;
            registeredPorts.put(port, null);
            return port;
      }
  }

  private void unregisterPort(int port) {
      synchronized (registeredPorts) {
          registeredPorts.remove(port);
      }
  }
}

package haveno.core.util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Utils;

import haveno.common.crypto.Encryption;

/**
 * This utility generates and prints public/private keypairs
 * which can be used to register arbitrators on the network.
 */
public class GenerateKeypairs {
    
    public static void main(String[] args) {
        
        // generate public/private keypairs
        List<SecretKey> secretKeys = new ArrayList<SecretKey>();
        for (int i = 0; i < 20; i++) {
            secretKeys.add(Encryption.generateSecretKey(256));
        }

        // print keypairs
        System.out.println("Private keys:");
        for (SecretKey sk : secretKeys) {
            String privKey = Utils.HEX.encode(sk.getEncoded());
            System.out.println(privKey);
        }
        System.out.println("Corresponding public keys:");
        for (SecretKey sk : secretKeys) {
            String privKey = Utils.HEX.encode(sk.getEncoded());
            ECKey ecKey = ECKey.fromPrivate(new BigInteger(1, Utils.HEX.decode(privKey)));
            String pubKey = Utils.HEX.encode(ecKey.getPubKey());
            System.out.println(pubKey);
        }
    }
}

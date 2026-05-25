package haveno.core.payment.payload;

import java.util.List;

public class PaymentMethodSanityChecker {
    public static void main(String[] args) {
        List<PaymentMethod> methods = PaymentMethod.getPaymentMethods();
        System.out.println("\n==========================================================");
        System.out.println("===   OFFSEC DIAGNOSTIC: UNLOCKED PAYMENT METHODS      ===");
        System.out.println("==========================================================");
        System.out.println("TOTAL ACTIVE METHODS FOUND IN DAEMON: " + methods.size());
        System.out.println("----------------------------------------------------------");
        
        for (PaymentMethod m : methods) {
            System.out.println("[ONLINE] -> " + m.getId());
        }
        
        System.out.println("----------------------------------------------------------");
        boolean hasBizum = methods.stream().anyMatch(m -> m.getId().equals("BIZUM"));
        boolean hasPaysera = methods.stream().anyMatch(m -> m.getId().equals("PAYSERA"));
        
        System.out.println("CRITICAL TARGETS VALIDATION:");
        System.out.println("BIZUM STATUS: " + (hasBizum ? "🔥 UNLOCKED & ACTIVE" : "❌ BLOCKED"));
        System.out.println("PAYSERA STATUS: " + (hasPaysera ? "🔥 UNLOCKED & ACTIVE" : "❌ BLOCKED"));
        System.out.println("==========================================================\n");
    }
}

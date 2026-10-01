import java.io.Console;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * Prints the bcrypt hash of a password typed at the console, for RECON_OPERATOR_PASSWORD_HASH and
 * RECON_VIEWER_PASSWORD_HASH in .env (TDD 11.1). The password is read without echo, asked twice,
 * and never printed. Run it with the command in .env.example.
 */
public class HashPassword {

    private static final int COST = 12;
    private static final int MIN_LENGTH = 12;
    // bcrypt reads no more than this; a longer password would match any password it starts with.
    private static final int MAX_BYTES = 72;

    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) {
            System.err.println("Run this in an interactive console: the password is read there, without echo.");
            System.exit(2);
        }
        char[] password = console.readPassword("Password: ");
        char[] again = console.readPassword("Again: ");
        byte[] bytes = new byte[0];
        try {
            if (password == null || again == null || !Arrays.equals(password, again)) {
                fail("The two entries differ.");
            }
            ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
            bytes = Arrays.copyOf(encoded.array(), encoded.limit());
            Arrays.fill(encoded.array(), (byte) 0);
            if (password.length < MIN_LENGTH || bytes.length > MAX_BYTES) {
                fail("A password is at least " + MIN_LENGTH + " characters and at most " + MAX_BYTES + " bytes.");
            }
            System.out.println(BCrypt.hashpw(bytes, BCrypt.gensalt(COST)));
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (again != null) {
                Arrays.fill(again, '\0');
            }
        }
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}

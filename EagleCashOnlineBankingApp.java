import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * EagleCashOnlineBankingApp - A Java Swing banking app backed by a real
 * SQLite database (not an in-memory HashMap). Data survives between runs -
 * it's saved to a file called "bank.db" next to this program.
 *
 * FEATURES: Register, Login, Deposit, Withdraw, Transfer Funds (to another
 * registered user by email), and Transaction History - all persisted to
 * SQLite via JDBC.
 *
 * REQUIRED SETUP - this needs two jars on your classpath:
 *   1. SQLite JDBC driver:
 *      https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar
 *   2. SLF4J API (a logging library sqlite-jdbc depends on):
 *      https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar
 *   Put both jars in the same folder as this .java file, then:
 *   3. Compile:
 *        javac -cp sqlite-jdbc-3.46.1.0.jar;slf4j-api-2.0.13.jar EagleCashOnlineBankingApp.java
 *   4. Run:
 *        java -cp .;sqlite-jdbc-3.46.1.0.jar;slf4j-api-2.0.13.jar EagleCashOnlineBankingApp   (Windows)
 *        java -cp .:sqlite-jdbc-3.46.1.0.jar:slf4j-api-2.0.13.jar EagleCashOnlineBankingApp   (Mac/Linux)
 *
 * IMPORTANT: the jar filenames in these commands must exactly match the
 * files actually sitting in your folder (check with `dir` / `ls`). If you
 * ever download a different version, update these commands to match -
 * Java will not report an error for a wrong/missing jar name in -cp, it
 * will just silently fail to find the driver, which looks like this same
 * "Missing Driver" dialog.
 *
 * A file named bank.db will appear in the folder the first time you run it -
 * that's your whole database, viewable with any SQLite browser tool.
 */
public class EagleCashOnlineBankingApp extends JFrame {

    // ---- Currently logged-in user ----
    private User currentUser;

    // ---- Layout manager that lets us switch between panels/screens ----
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel mainPanel = new JPanel(cardLayout);

    // Maps each screen name to the field that should receive the text cursor
    // as soon as that screen is shown (e.g. the email field on Login). Filled
    // in by each build*Screen() method as it creates its fields.
    private final Map<String, JComponent> firstFieldByScreen = new HashMap<>();

    /**
     * Switches to the given screen and puts the text cursor in that screen's
     * first field, so you can start typing immediately without clicking into
     * a field first. Used everywhere instead of calling cardLayout.show()
     * directly, so this behavior is automatic and consistent on every screen.
     */
    private void showScreen(String screenName) {
        cardLayout.show(mainPanel, screenName);
        JComponent firstField = firstFieldByScreen.get(screenName);
        if (firstField != null) {
            // invokeLater lets the card fully become visible first, so the
            // focus request isn't lost due to timing with the layout swap.
            SwingUtilities.invokeLater(firstField::requestFocusInWindow);
        }
    }

    // Screen name constants
    private static final String LOGIN_SCREEN = "LOGIN";
    private static final String REGISTER_SCREEN = "REGISTER";
    private static final String DASHBOARD_SCREEN = "DASHBOARD";
    private static final String HISTORY_SCREEN = "HISTORY";
    private static final String TRANSFER_SCREEN = "TRANSFER";
    private static final String FORGOT_EMAIL_SCREEN = "FORGOT_EMAIL";
    private static final String FORGOT_OTP_SCREEN = "FORGOT_OTP";
    private static final String FORGOT_RESET_SCREEN = "FORGOT_RESET";
    private static final String ADMIN_LOGIN_SCREEN = "ADMIN_LOGIN";
    private static final String ADMIN_DASHBOARD_SCREEN = "ADMIN_DASHBOARD";

    private JLabel balanceLabel;
    private JLabel welcomeLabel;
    private DefaultListModel<String> historyListModel;
    private JTextField transferEmailField;
    private JTextField transferAmountField;
    private JLabel transferMessageLabel;

    // Forgot Password flow - tracks which account (identified by email or
    // mobile number) is going through the reset process as the user moves
    // between the three forgot-password screens.
    private String forgotEmail;
    private JTextField forgotEmailField;
    private JLabel forgotEmailMessageLabel;
    private JTextField forgotOtpField;
    private JLabel forgotOtpMessageLabel;
    private JPasswordField forgotNewPinField;
    private JPasswordField forgotConfirmPinField;
    private JLabel forgotResetMessageLabel;

    // Admin monitoring screens
    private JLabel adminUserCountLabel;
    private DefaultListModel<String> adminUserListModel;
    private JLabel adminLoginMessageLabel;
    private JTextField adminUsernameField;
    // Keeps the same order as adminUserListModel, so adminUsersCache.get(i)
    // is always the User behind row i - used to look up whose transactions
    // to show when a row in the admin list is double-clicked.
    private final List<User> adminUsersCache = new ArrayList<>();

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

    // Philippine peso sign (\u20B1 = "PHP"). Written as a unicode escape so it
    // compiles correctly no matter what text encoding your editor/terminal uses.
    private static final String CURRENCY_SYMBOL = "\u20B1";

    // ---- Navy blue theme colors ----
    // Applied to every screen's background panel. Text colors below are chosen
    // for contrast against this navy so labels stay readable.
    private static final Color NAVY_BLUE = new Color(9, 30, 62);
    private static final Color LIGHT_TEXT = new Color(235, 240, 250);
    // Brighter than plain green - plain (0,128,0) is too dark to read well on navy.
    private static final Color SUCCESS_GREEN = new Color(102, 230, 140);
    // Flat, solid button background (no gradient/bevel) - light enough to keep
    // navy button text readable, distinct enough to stand out from the navy panels.
    private static final Color BUTTON_FLAT_BG = new Color(214, 224, 240);

    // ---- Admin account ----
    // A fixed username/password, separate from the users table entirely -
    // the admin isn't a bank customer, just whoever monitors the system.
    //
    // DEMO ONLY: a hardcoded plaintext password is NOT how you'd do this in
    // a real production app (you'd store a hashed password in its own table,
    // same as customer PINs ideally should be too). This keeps the school
    // project simple, but change these before showing this to anyone else,
    // and don't reuse this pattern for anything with real money or data.
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    public EagleCashOnlineBankingApp() {
        super("EagleCash Online Banking App");
        // DO_NOTHING_ON_CLOSE hands control of the (x) button entirely to the
        // windowClosing() handler below, which asks for confirmation before the
        // app actually exits - on whatever screen happens to be showing.
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(460, 560);
        setLocationRelativeTo(null);
        setResizable(false);

        // Replaces the default Java coffee-cup icon in the title bar / taskbar
        // with the eagle image, if it was loaded successfully.
        Image logoForIcon = loadLogoImage();
        if (logoForIcon != null) {
            setIconImage(logoForIcon);
        }

        DatabaseHelper.initDatabase();

        mainPanel.add(buildLoginScreen(), LOGIN_SCREEN);
        mainPanel.add(buildRegisterScreen(), REGISTER_SCREEN);
        mainPanel.add(buildDashboardScreen(), DASHBOARD_SCREEN);
        mainPanel.add(buildHistoryScreen(), HISTORY_SCREEN);
        mainPanel.add(buildTransferScreen(), TRANSFER_SCREEN);
        mainPanel.add(buildForgotEmailScreen(), FORGOT_EMAIL_SCREEN);
        mainPanel.add(buildForgotOtpScreen(), FORGOT_OTP_SCREEN);
        mainPanel.add(buildForgotResetScreen(), FORGOT_RESET_SCREEN);
        mainPanel.add(buildAdminLoginScreen(), ADMIN_LOGIN_SCREEN);
        mainPanel.add(buildAdminDashboardScreen(), ADMIN_DASHBOARD_SCREEN);

        add(mainPanel);
        showScreen(LOGIN_SCREEN);

        // A focus request made before the window is actually showing on
        // screen (which is the case for showScreen() above, since this
        // still runs inside the constructor) can silently be ignored by
        // Swing. windowOpened() fires once the window genuinely appears,
        // so this guarantees the very first field on launch gets focus too -
        // every screen reached afterward is already handled by showScreen().
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                JComponent firstField = firstFieldByScreen.get(LOGIN_SCREEN);
                if (firstField != null) {
                    firstField.requestFocusInWindow();
                }
            }

            // Fires whenever the user clicks the window's (x) close button,
            // regardless of which screen is currently showing. Asking here -
            // in one place - covers every screen automatically.
            @Override
            public void windowClosing(WindowEvent e) {
                int choice = JOptionPane.showConfirmDialog(
                        EagleCashOnlineBankingApp.this,
                        "Are you sure you want to exit EagleCash?",
                        "Confirm Exit",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                if (choice == JOptionPane.YES_OPTION) {
                    dispose();
                    System.exit(0);
                }
                // NO (or closing the confirm dialog itself) simply does
                // nothing further, leaving the app open and untouched.
            }
        });
    }

    // ------------------------------------------------------------------
    // LOGO IMAGE + HEADER
    // ------------------------------------------------------------------

    /** Filename of the logo image. Must sit in the same folder as this .java/.class file. */
    private static final String LOGO_FILENAME = "eagle_logo.png";
    private static final int LOGO_HEIGHT = 56; // pixels; width is scaled to match the image's aspect ratio

    private static Image cachedLogoImage;
    private static boolean loggedMissingLogo = false;

    /** Loads eagle_logo.png once and reuses it. Returns null (without crashing) if the file is missing. */
    private static Image loadLogoImage() {
        if (cachedLogoImage != null) return cachedLogoImage;
        try {
            cachedLogoImage = ImageIO.read(new File(LOGO_FILENAME));
        } catch (Exception e) {
            if (!loggedMissingLogo) {
                System.err.println("Could not load " + LOGO_FILENAME + ": " + e.getMessage());
                System.err.println("Make sure " + LOGO_FILENAME + " is in the same folder as this program.");
                loggedMissingLogo = true;
            }
        }
        return cachedLogoImage;
    }

    /**
     * Picks the first available script/calligraphy-style font installed on this
     * machine (Windows ships "Segoe Script" by default; a few common alternatives
     * are checked too). Falls back to an italic serif font if none are found, so
     * the tagline still looks reasonably decorative on any computer.
     */
    private static String pickCalligraphyFont() {
        String[] preferred = {"Segoe Script", "Brush Script MT", "Lucida Handwriting", "Monotype Corsiva"};
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String name : preferred) {
            if (available.contains(name)) return name;
        }
        return Font.SERIF; // reasonable fallback; combined with ITALIC below
    }

    private static final String CALLIGRAPHY_FONT = pickCalligraphyFont();

    /**
     * Header shown at the top of every screen: the eagle logo sits directly
     * beside "EagleCash" and the tagline, and the whole group is centered
     * together in the header (not the logo pinned to one edge separately).
     */
    private JPanel buildHeader() {
        // FlowLayout.CENTER groups the logo + text as a single unit and centers
        // that unit as a whole, so the image stays right next to the text no
        // matter how wide the window is.
        JPanel header = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 6));
        header.setBorder(BorderFactory.createEmptyBorder(20, 10, 6, 10));
        header.setBackground(NAVY_BLUE);
        header.setOpaque(true);

        Image logoImage = loadLogoImage();
        JLabel logoLabel;
        if (logoImage != null) {
            int targetWidth = Math.max(1,
                    (int) Math.round(logoImage.getWidth(null) * (LOGO_HEIGHT / (double) logoImage.getHeight(null))));
            Image scaled = logoImage.getScaledInstance(targetWidth, LOGO_HEIGHT, Image.SCALE_SMOOTH);
            logoLabel = new JLabel(new ImageIcon(scaled));
        } else {
            // Friendly fallback if eagle_logo.png isn't in the folder, instead of crashing.
            logoLabel = new JLabel("(eagle_logo.png not found)");
            logoLabel.setFont(new Font("Tahoma", Font.ITALIC, 10));
            logoLabel.setForeground(LIGHT_TEXT);
        }
        header.add(logoLabel);

        JPanel textPanel = new JPanel();
        textPanel.setOpaque(false);
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

        JLabel appName = new JLabel("EagleCash");
        appName.setFont(new Font("Tahoma", Font.BOLD, 20));
        appName.setAlignmentX(Component.CENTER_ALIGNMENT);
        appName.setForeground(LIGHT_TEXT);

        // Wrapped onto two lines (instead of one long line) so it stays readable,
        // and centered using HTML since a plain JLabel won't wrap or center
        // multi-line text on its own.
        JLabel tagline = new JLabel(
                "<html><div style='text-align:center;'>" +
                "Bank Smarter. Bank Securely.<br>" +
                "Bank with EagleCash</div></html>");
        tagline.setFont(new Font(CALLIGRAPHY_FONT, Font.ITALIC, 14));
        tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
        tagline.setHorizontalAlignment(SwingConstants.CENTER);
        tagline.setForeground(LIGHT_TEXT);

        textPanel.add(Box.createVerticalGlue());
        textPanel.add(Box.createVerticalStrut(10)); // nudges "EagleCash" down a bit from the top
        textPanel.add(appName);
        textPanel.add(Box.createVerticalStrut(4));
        textPanel.add(tagline);
        textPanel.add(Box.createVerticalGlue());

        header.add(textPanel);

        return header;
    }

    /** Wraps a screen's content panel with the shared header along the top. */
    private JPanel wrapWithHeader(JPanel content) {
        applyNavyTheme(content);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(NAVY_BLUE);
        wrapper.setOpaque(true);
        wrapper.add(buildHeader(), BorderLayout.NORTH);
        wrapper.add(content, BorderLayout.CENTER);
        return wrapper;
    }

    /**
     * Paints a screen's content panel navy blue and switches JLabel text to a
     * light color so it stays readable on the dark background. Labels that
     * were already given a custom color (the red/green status messages) are
     * left alone, since those already have enough contrast against navy.
     *
     * NOTE: this checks against RED/SUCCESS_GREEN rather than "is it black"
     * on purpose - the Look-and-Feel's actual default label color isn't
     * necessarily pure Color.BLACK (it can be a muted dark gray), so
     * checking equality against black missed labels and left them low
     * contrast on navy. Checking against the two colors we deliberately set
     * elsewhere is more reliable.
     */
    private void applyNavyTheme(JPanel content) {
        content.setBackground(NAVY_BLUE);
        content.setOpaque(true);
        for (Component c : content.getComponents()) {
            if (c instanceof JLabel) {
                JLabel label = (JLabel) c;
                Color fg = label.getForeground();
                if (!fg.equals(Color.RED) && !fg.equals(SUCCESS_GREEN)) {
                    label.setForeground(LIGHT_TEXT);
                }
            } else if (c instanceof JButton) {
                flattenButton((JButton) c);
            }
        }
    }

    /**
     * Strips the Look-and-Feel's default raised/gradient chrome off a button
     * and replaces it with a flat, solid-color look (flat background, no
     * bevel, no focus ring) that matches the app's navy theme. Applied
     * automatically to every button on every screen via applyNavyTheme, so
     * individual build*Screen() methods don't need to style buttons by hand.
     */
    private static void flattenButton(JButton button) {
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setBackground(BUTTON_FLAT_BG);
        button.setForeground(NAVY_BLUE);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        button.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /**
     * Makes pressing Enter anywhere on a screen click that screen's primary
     * button, the same as clicking it with the mouse.
     * WHEN_ANCESTOR_OF_FOCUSED_COMPONENT scopes this to only fire while a
     * component inside THIS panel has focus, so it won't interfere with
     * Enter on other screens.
     */
    private static void bindEnterToButton(JPanel panel, JButton button) {
        panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("ENTER"), "primaryEnterAction");
        panel.getActionMap().put("primaryEnterAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                button.doClick();
            }
        });
    }

    // ------------------------------------------------------------------
    // Simple data classes
    // ------------------------------------------------------------------
    private static class User {
        int id;
        String name;
        String email;
        String mobileNumber;
        String pin;
        double balance;
    }

    private static class Transaction {
        String type;
        double amount;
        String timestamp; // stored as ISO text in the DB
        String targetEmail; // the other party's email, only set for transfers
        String targetMobileNumber; // the other party's mobile number, only set for transfers (may be null if they have none on file)

        @Override
        public String toString() {
            String displayTime = timestamp;
            try {
                displayTime = LocalDateTime.parse(timestamp).format(TIME_FORMAT);
            } catch (Exception ignored) {
                // If parsing fails for any reason, fall back to the raw stored text.
            }

            String sign = (type.equals("Deposit") || type.equals("Transfer Received")) ? "+" : "-";

            // The other party's contact info, shown together as "email / mobile"
            // when both are on file, or whichever one is available.
            String contact = targetEmail;
            if (targetMobileNumber != null && !targetMobileNumber.isEmpty()) {
                contact = (contact == null || contact.isEmpty())
                        ? targetMobileNumber
                        : contact + " / " + targetMobileNumber;
            }

            String label = type;
            if ("Transfer Sent".equals(type) && contact != null) {
                label = "Transfer to " + contact;
            } else if ("Transfer Received".equals(type) && contact != null) {
                label = "Transfer from " + contact;
            }

            String amountText = sign + CURRENCY_SYMBOL + String.format("%.2f", amount);

            // Fixed-width columns (matches HISTORY_COLUMN_HEADER below) so the
            // list lines up neatly under the header row in the Monospaced font.
            return String.format("%-22s %-12s %s", displayTime, amountText, label);
        }
    }

    // Header row shown above the transaction history list - column widths
    // must match the %-22s / %-12s spacing used in Transaction.toString().
    private static final String HISTORY_COLUMN_HEADER =
            String.format("%-22s %-12s %s", "Date & Time", "Amount", "Description");

    // Header row shown above the admin user list - column widths must match
    // the "#%-4d %-20s %-28s %-14s %s%.2f" format used in refreshAdminDashboard().
    private static final String ADMIN_COLUMN_HEADER =
            String.format("%-5s %-20s %-28s %-14s %s", "ID", "Name", "Email", "Mobile", "Balance");

    // ------------------------------------------------------------------
    // DATABASE HELPER - all SQL lives here
    // ------------------------------------------------------------------
    private static class DatabaseHelper {
        private static final String DB_URL = "jdbc:sqlite:bank.db";

        /** Opens a fresh connection. SQLite connections are cheap to open/close per operation. */
        private static Connection connect() throws SQLException {
            return DriverManager.getConnection(DB_URL);
        }

        /** Creates the users and transactions tables if they don't already exist. */
        static void initDatabase() {
            // sqlite-jdbc extracts a native library file to a temp folder when it
            // loads. On some locked-down machines (school/lab computers) the
            // default system temp folder isn't writable, which makes the driver
            // silently fail to register - showing up as "No suitable driver
            // found" instead of a clear permissions error. Pointing it at the
            // current folder instead avoids that.
            System.setProperty("org.sqlite.tmpdir", System.getProperty("user.dir"));

            // Explicitly load the driver class. Modern JDBC drivers normally
            // auto-register themselves, but forcing this here avoids
            // "No suitable driver found" errors caused by classpath quirks.
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                JOptionPane.showMessageDialog(null,
                        "The SQLite driver jar was not found on the classpath.\n" +
                        "Make sure the sqlite-jdbc jar (and slf4j-api jar) are\n" +
                        "in this folder and included in your -cp argument\n" +
                        "when compiling and running the program.",
                        "Missing Driver", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }

            String usersTable =
                    "CREATE TABLE IF NOT EXISTS users (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  name TEXT NOT NULL," +
                    "  email TEXT NOT NULL UNIQUE," +
                    "  mobile_number TEXT," +
                    "  pin TEXT NOT NULL," +
                    "  balance REAL NOT NULL DEFAULT 0," +
                    "  otp TEXT," +
                    "  otp_expiry TEXT" +
                    ")";
            String transactionsTable =
                    "CREATE TABLE IF NOT EXISTS transactions (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  user_id INTEGER NOT NULL," +
                    "  type TEXT NOT NULL," +
                    "  amount REAL NOT NULL," +
                    "  timestamp TEXT NOT NULL," +
                    "  target_email TEXT," +
                    "  target_mobile TEXT," +
                    "  FOREIGN KEY(user_id) REFERENCES users(id)" +
                    ")";
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement()) {
                stmt.execute(usersTable);
                stmt.execute(transactionsTable);
            } catch (SQLException e) {
                // A beginner-friendly fatal error dialog, since there's no point
                // continuing if the database can't even set itself up.
                e.printStackTrace(); // full details in the console/terminal
                JOptionPane.showMessageDialog(null,
                        "Could not set up the database:\n" + e.getMessage(),
                        "Database Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }

            // Migrations: older copies of bank.db created before these features
            // existed won't have these columns yet. Adding them here means you
            // don't have to delete your existing database. If a column already
            // exists, SQLite throws an error that we simply ignore.
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE transactions ADD COLUMN target_email TEXT");
            } catch (SQLException alreadyExists) {
                // Expected on every run after the first - the column is already there.
            }
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE transactions ADD COLUMN target_mobile TEXT");
            } catch (SQLException alreadyExists) {
                // Expected on every run after the first - the column is already there.
            }
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE users ADD COLUMN otp TEXT");
            } catch (SQLException alreadyExists) {
                // Expected on every run after the first - the column is already there.
            }
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE users ADD COLUMN otp_expiry TEXT");
            } catch (SQLException alreadyExists) {
                // Expected on every run after the first - the column is already there.
            }
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE users ADD COLUMN mobile_number TEXT");
            } catch (SQLException alreadyExists) {
                // Expected on every run after the first - the column is already there.
            }
        }

        static boolean emailExists(String email) throws SQLException {
            String sql = "SELECT 1 FROM users WHERE email = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        /** Used during registration so two accounts can't share the same mobile number. */
        static boolean mobileNumberExists(String mobileNumber) throws SQLException {
            String sql = "SELECT 1 FROM users WHERE mobile_number = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, mobileNumber);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        static void insertUser(String name, String email, String mobileNumber, String pin) throws SQLException {
            String sql = "INSERT INTO users(name, email, mobile_number, pin, balance) VALUES (?, ?, ?, ?, 0)";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, email);
                ps.setString(3, mobileNumber);
                ps.setString(4, pin);
                ps.executeUpdate();
            }
        }

        // ---- Admin monitoring queries ----

        /** Total number of registered accounts - the headline number on the Admin Dashboard. */
        static int getUserCount() throws SQLException {
            String sql = "SELECT COUNT(*) AS total FROM users";
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                rs.next();
                return rs.getInt("total");
            }
        }

        /**
         * All registered users for the admin monitoring list. Deliberately
         * does NOT select the pin column - admin can see who has registered
         * and their balance, but never anyone's PIN.
         */
        static List<User> getAllUsers() throws SQLException {
            String sql = "SELECT id, name, email, mobile_number, balance FROM users ORDER BY id";
            List<User> results = new ArrayList<>();
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    User u = new User();
                    u.id = rs.getInt("id");
                    u.name = rs.getString("name");
                    u.email = rs.getString("email");
                    u.mobileNumber = rs.getString("mobile_number");
                    u.balance = rs.getDouble("balance");
                    results.add(u);
                }
            }
            return results;
        }

        /**
         * Generates a 6-digit OTP, stores it against the matching account (looked
         * up by email OR mobile number) with a 5-minute expiry, and returns it so
         * the UI can display it.
         *
         * NOTE: A real production app would email this code instead of
         * displaying it on screen (that's what the EagleCash design doc calls
         * for). Sending real email needs an SMTP server and credentials,
         * which is a lot of extra setup for a school project running on a
         * lab computer, so this demo version shows the code directly instead.
         */
        static String generateOtp(String identifier) throws SQLException {
            String otp = String.format("%06d", new java.util.Random().nextInt(1_000_000));
            String expiry = LocalDateTime.now().plusMinutes(5).toString();
            String sql = "UPDATE users SET otp = ?, otp_expiry = ? WHERE email = ? OR mobile_number = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, otp);
                ps.setString(2, expiry);
                ps.setString(3, identifier);
                ps.setString(4, identifier);
                ps.executeUpdate();
            }
            return otp;
        }

        /** Returns true if the entered OTP matches the stored one and hasn't expired yet. */
        static boolean verifyOtp(String identifier, String enteredOtp) throws SQLException {
            String sql = "SELECT otp, otp_expiry FROM users WHERE email = ? OR mobile_number = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, identifier);
                ps.setString(2, identifier);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    String storedOtp = rs.getString("otp");
                    String expiryText = rs.getString("otp_expiry");
                    if (storedOtp == null || expiryText == null) return false;
                    if (!storedOtp.equals(enteredOtp)) return false;
                    LocalDateTime expiry = LocalDateTime.parse(expiryText);
                    return LocalDateTime.now().isBefore(expiry);
                }
            }
        }

        /** Sets a new PIN and clears out the used OTP so it can't be replayed. */
        static void resetPin(String identifier, String newPin) throws SQLException {
            String sql = "UPDATE users SET pin = ?, otp = NULL, otp_expiry = NULL WHERE email = ? OR mobile_number = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, newPin);
                ps.setString(2, identifier);
                ps.setString(3, identifier);
                ps.executeUpdate();
            }
        }

        /** Returns the matching user, or null if the email-or-mobile/PIN combination doesn't match. */
        static User login(String identifier, String pin) throws SQLException {
            String sql = "SELECT id, name, email, mobile_number, pin, balance FROM users WHERE email = ? OR mobile_number = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, identifier);
                ps.setString(2, identifier);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;           // no account with that email/mobile
                    if (!rs.getString("pin").equals(pin)) return null; // wrong PIN

                    User user = new User();
                    user.id = rs.getInt("id");
                    user.name = rs.getString("name");
                    user.email = rs.getString("email");
                    user.mobileNumber = rs.getString("mobile_number");
                    user.pin = rs.getString("pin");
                    user.balance = rs.getDouble("balance");
                    return user;
                }
            }
        }

        static void updateBalance(int userId, double newBalance) throws SQLException {
            String sql = "UPDATE users SET balance = ? WHERE id = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setDouble(1, newBalance);
                ps.setInt(2, userId);
                ps.executeUpdate();
            }
        }

        static void insertTransaction(int userId, String type, double amount) throws SQLException {
            String sql = "INSERT INTO transactions(user_id, type, amount, timestamp, target_email) " +
                         "VALUES (?, ?, ?, ?, NULL)";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                ps.setString(2, type);
                ps.setDouble(3, amount);
                ps.setString(4, LocalDateTime.now().toString());
                ps.executeUpdate();
            }
        }

        static List<Transaction> getHistory(int userId) throws SQLException {
            String sql = "SELECT type, amount, timestamp, target_email, target_mobile FROM transactions " +
                         "WHERE user_id = ? ORDER BY id DESC";
            List<Transaction> results = new ArrayList<>();
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Transaction t = new Transaction();
                        t.type = rs.getString("type");
                        t.amount = rs.getDouble("amount");
                        t.timestamp = rs.getString("timestamp");
                        t.targetEmail = rs.getString("target_email");
                        t.targetMobileNumber = rs.getString("target_mobile");
                        results.add(t);
                    }
                }
            }
            return results;
        }

        /**
         * Transfers funds from one user to another as a single atomic database
         * transaction: both balance updates and both transaction log entries
         * either all succeed together or all get rolled back together, so
         * money can never "disappear" partway through if something fails.
         * The recipient can be identified by either their email or their
         * mobile number - whichever the sender typed in.
         */
        static void transferFunds(int fromUserId, String fromEmail, String toIdentifier, double amount)
                throws SQLException {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false); // start a manual transaction
                try {
                    int toUserId;
                    double toBalance;
                    String toEmail; // the recipient's actual email, for the transaction log,
                                     // even if the sender typed in a mobile number instead
                    String toMobile; // the recipient's mobile number on file (may be null)
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id, balance, email, mobile_number FROM users WHERE email = ? OR mobile_number = ?")) {
                        ps.setString(1, toIdentifier);
                        ps.setString(2, toIdentifier);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new SQLException("No account found with that recipient email or mobile number.");
                            }
                            toUserId = rs.getInt("id");
                            toBalance = rs.getDouble("balance");
                            toEmail = rs.getString("email");
                            toMobile = rs.getString("mobile_number");
                        }
                    }

                    if (toUserId == fromUserId) {
                        throw new SQLException("You can't transfer money to your own account.");
                    }

                    double fromBalance;
                    String fromMobile; // the sender's mobile number on file (may be null)
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT balance, mobile_number FROM users WHERE id = ?")) {
                        ps.setInt(1, fromUserId);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            fromBalance = rs.getDouble("balance");
                            fromMobile = rs.getString("mobile_number");
                        }
                    }
                    if (amount > fromBalance) {
                        throw new SQLException("Insufficient balance for this transfer.");
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE users SET balance = ? WHERE id = ?")) {
                        ps.setDouble(1, fromBalance - amount);
                        ps.setInt(2, fromUserId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE users SET balance = ? WHERE id = ?")) {
                        ps.setDouble(1, toBalance + amount);
                        ps.setInt(2, toUserId);
                        ps.executeUpdate();
                    }

                    String insertSql = "INSERT INTO transactions(user_id, type, amount, timestamp, target_email, target_mobile) " +
                                        "VALUES (?, ?, ?, ?, ?, ?)";
                    String now = LocalDateTime.now().toString();

                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setInt(1, fromUserId);
                        ps.setString(2, "Transfer Sent");
                        ps.setDouble(3, amount);
                        ps.setString(4, now);
                        ps.setString(5, toEmail);
                        ps.setString(6, toMobile);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setInt(1, toUserId);
                        ps.setString(2, "Transfer Received");
                        ps.setDouble(3, amount);
                        ps.setString(4, now);
                        ps.setString(5, fromEmail);
                        ps.setString(6, fromMobile);
                        ps.executeUpdate();
                    }

                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback(); // undo everything above if any step failed
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // LOGIN SCREEN
    // ------------------------------------------------------------------
    private JPanel buildLoginScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Login");
        title.setFont(new Font("Tahoma", Font.BOLD, 20));

        JLabel emailLabel = new JLabel("Email or Mobile:");
        JTextField emailField = new JTextField(18);
        firstFieldByScreen.put(LOGIN_SCREEN, emailField);

        JLabel pinLabel = new JLabel("PIN:");
        JPasswordField pinField = new JPasswordField(18);

        JButton loginButton = new JButton("Login");
        JButton goRegisterButton = new JButton("Create an account");
        JButton forgotPasswordButton = new JButton("Forgot Password?");
        JButton adminLoginButton = new JButton("Admin Login");

        JLabel messageLabel = new JLabel(" ");
        messageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(title, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1; gbc.gridx = 0; panel.add(emailLabel, gbc);
        gbc.gridx = 1; panel.add(emailField, gbc);

        gbc.gridy = 2; gbc.gridx = 0; panel.add(pinLabel, gbc);
        gbc.gridx = 1; panel.add(pinField, gbc);

        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(loginButton, gbc);

        gbc.gridy = 4;
        panel.add(goRegisterButton, gbc);

        gbc.gridy = 5;
        panel.add(forgotPasswordButton, gbc);

        gbc.gridy = 6;
        panel.add(adminLoginButton, gbc);

        gbc.gridy = 7;
        panel.add(messageLabel, gbc);

        loginButton.addActionListener(e -> {
            String identifier = emailField.getText().trim();
            String pin = new String(pinField.getPassword());

            try {
                User user = DatabaseHelper.login(identifier, pin);
                if (user == null) {
                    messageLabel.setText("Incorrect email/mobile or PIN.");
                } else {
                    currentUser = user;
                    messageLabel.setText(" ");
                    emailField.setText("");
                    pinField.setText("");
                    refreshDashboard();
                    showScreen(DASHBOARD_SCREEN);
                }
            } catch (SQLException ex) {
                messageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        goRegisterButton.addActionListener(e -> {
            messageLabel.setText(" ");
            showScreen(REGISTER_SCREEN);
        });

        forgotPasswordButton.addActionListener(e -> {
            messageLabel.setText(" ");
            forgotEmailField.setText("");
            forgotEmailMessageLabel.setForeground(Color.RED);
            forgotEmailMessageLabel.setText(" ");
            showScreen(FORGOT_EMAIL_SCREEN);
        });

        adminLoginButton.addActionListener(e -> {
            messageLabel.setText(" ");
            adminUsernameField.setText("");
            adminLoginMessageLabel.setForeground(Color.RED);
            adminLoginMessageLabel.setText(" ");
            showScreen(ADMIN_LOGIN_SCREEN);
        });

        // Pressing Enter while focus is anywhere on the login screen (email
        // field, PIN field, etc.) clicks the Login button, same as clicking
        // it with the mouse.
        bindEnterToButton(panel, loginButton);

        return wrapWithHeader(panel);
    }

    // ------------------------------------------------------------------
    // REGISTER SCREEN
    // ------------------------------------------------------------------
    private JPanel buildRegisterScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Create Account");
        title.setFont(new Font("Tahoma", Font.BOLD, 20));

        JLabel nameLabel = new JLabel("Name:");
        JTextField nameField = new JTextField(18);
        firstFieldByScreen.put(REGISTER_SCREEN, nameField);

        JLabel emailLabel = new JLabel("Email:");
        JTextField emailField = new JTextField(18);

        JLabel mobileLabel = new JLabel("Mobile Number:");
        JTextField mobileField = new JTextField(18);

        JLabel pinLabel = new JLabel("PIN (4+ digits):");
        JPasswordField pinField = new JPasswordField(18);

        JButton registerButton = new JButton("Register");
        JButton backButton = new JButton("Back to login");

        JLabel messageLabel = new JLabel(" ");
        messageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(title, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1; gbc.gridx = 0; panel.add(nameLabel, gbc);
        gbc.gridx = 1; panel.add(nameField, gbc);

        gbc.gridy = 2; gbc.gridx = 0; panel.add(emailLabel, gbc);
        gbc.gridx = 1; panel.add(emailField, gbc);

        gbc.gridy = 3; gbc.gridx = 0; panel.add(mobileLabel, gbc);
        gbc.gridx = 1; panel.add(mobileField, gbc);

        gbc.gridy = 4; gbc.gridx = 0; panel.add(pinLabel, gbc);
        gbc.gridx = 1; panel.add(pinField, gbc);

        gbc.gridy = 5; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(registerButton, gbc);

        gbc.gridy = 6;
        panel.add(backButton, gbc);

        gbc.gridy = 7;
        panel.add(messageLabel, gbc);

        registerButton.addActionListener(e -> {
            String name = nameField.getText().trim();
            String email = emailField.getText().trim();
            String mobileNumber = mobileField.getText().trim();
            String pin = new String(pinField.getPassword());

            if (name.isEmpty() || email.isEmpty() || mobileNumber.isEmpty() || pin.isEmpty()) {
                messageLabel.setText("Please fill in every field.");
                return;
            }
            if (!email.contains("@") || !email.contains(".")) {
                messageLabel.setText("Please enter a valid email.");
                return;
            }
            if (!mobileNumber.matches("\\d{10,13}")) {
                messageLabel.setText("Mobile number must be 10-13 digits.");
                return;
            }
            if (!pin.matches("\\d{4,}")) {
                messageLabel.setText("PIN must be at least 4 digits.");
                return;
            }

            try {
                if (DatabaseHelper.emailExists(email)) {
                    messageLabel.setText("An account with that email already exists.");
                    return;
                }
                if (DatabaseHelper.mobileNumberExists(mobileNumber)) {
                    messageLabel.setText("An account with that mobile number already exists.");
                    return;
                }
                DatabaseHelper.insertUser(name, email, mobileNumber, pin);
                nameField.setText("");
                emailField.setText("");
                mobileField.setText("");
                pinField.setText("");
                messageLabel.setText(" ");

                JOptionPane.showMessageDialog(panel,
                        "Account created! You can log in now.",
                        "Registration Successful", JOptionPane.INFORMATION_MESSAGE);
                // showMessageDialog blocks until the user clicks OK, so this
                // line only runs after they dismiss the popup.
                showScreen(LOGIN_SCREEN);
            } catch (SQLException ex) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        backButton.addActionListener(e -> {
            messageLabel.setForeground(Color.RED);
            messageLabel.setText(" ");
            showScreen(LOGIN_SCREEN);
        });

        bindEnterToButton(panel, registerButton);

        return wrapWithHeader(panel);
    }

    // ------------------------------------------------------------------
    // DASHBOARD SCREEN
    // ------------------------------------------------------------------
    private JPanel buildDashboardScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        welcomeLabel = new JLabel("Welcome!");
        welcomeLabel.setFont(new Font("Tahoma", Font.BOLD, 18));

        balanceLabel = new JLabel("Balance: " + CURRENCY_SYMBOL + "0.00");
        balanceLabel.setFont(new Font("Tahoma", Font.PLAIN, 16));

        JTextField amountField = new JTextField(10);
        firstFieldByScreen.put(DASHBOARD_SCREEN, amountField);
        JButton depositButton = new JButton("Deposit");
        JButton withdrawButton = new JButton("Withdraw");
        JButton transferButton = new JButton("Transfer Funds");
        JButton historyButton = new JButton("Transaction History");
        JButton logoutButton = new JButton("Logout");

        JLabel messageLabel = new JLabel(" ");
        messageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(welcomeLabel, gbc);

        gbc.gridy = 1;
        panel.add(balanceLabel, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 2; gbc.gridx = 0;
        panel.add(new JLabel("Amount:"), gbc);
        gbc.gridx = 1;
        panel.add(amountField, gbc);

        gbc.gridy = 3; gbc.gridx = 0;
        panel.add(depositButton, gbc);
        gbc.gridx = 1;
        panel.add(withdrawButton, gbc);

        gbc.gridy = 4; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(transferButton, gbc);

        gbc.gridy = 5;
        panel.add(historyButton, gbc);

        gbc.gridy = 6;
        panel.add(logoutButton, gbc);

        gbc.gridy = 7;
        panel.add(messageLabel, gbc);

        depositButton.addActionListener(e -> {
            Double amount = parseAmount(amountField.getText(), messageLabel);
            if (amount == null) return;

            int choice = JOptionPane.showConfirmDialog(panel,
                    "Deposit " + CURRENCY_SYMBOL + String.format("%.2f", amount) + "?",
                    "Confirm Deposit", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;

            try {
                double newBalance = currentUser.balance + amount;
                DatabaseHelper.updateBalance(currentUser.id, newBalance);
                DatabaseHelper.insertTransaction(currentUser.id, "Deposit", amount);
                currentUser.balance = newBalance;
                messageLabel.setForeground(SUCCESS_GREEN);
                messageLabel.setText("Deposited " + CURRENCY_SYMBOL + String.format("%.2f", amount));
                amountField.setText("");
                refreshDashboard();
            } catch (SQLException ex) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        withdrawButton.addActionListener(e -> {
            Double amount = parseAmount(amountField.getText(), messageLabel);
            if (amount == null) return;
            if (amount > currentUser.balance) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Insufficient balance.");
                return;
            }

            int choice = JOptionPane.showConfirmDialog(panel,
                    "Withdraw " + CURRENCY_SYMBOL + String.format("%.2f", amount) + "?",
                    "Confirm Withdrawal", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;

            try {
                double newBalance = currentUser.balance - amount;
                DatabaseHelper.updateBalance(currentUser.id, newBalance);
                DatabaseHelper.insertTransaction(currentUser.id, "Withdraw", amount);
                currentUser.balance = newBalance;
                messageLabel.setForeground(SUCCESS_GREEN);
                messageLabel.setText("Withdrew " + CURRENCY_SYMBOL + String.format("%.2f", amount));
                amountField.setText("");
                refreshDashboard();
            } catch (SQLException ex) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        transferButton.addActionListener(e -> {
            transferEmailField.setText("");
            transferAmountField.setText("");
            transferMessageLabel.setForeground(Color.RED);
            transferMessageLabel.setText(" ");
            showScreen(TRANSFER_SCREEN);
        });

        historyButton.addActionListener(e -> {
            refreshHistoryList();
            showScreen(HISTORY_SCREEN);
        });

        logoutButton.addActionListener(e -> {
            currentUser = null;
            messageLabel.setForeground(Color.RED);
            messageLabel.setText(" ");
            amountField.setText("");
            showScreen(LOGIN_SCREEN);
        });

        return wrapWithHeader(panel);
    }

    // ------------------------------------------------------------------
    // TRANSACTION HISTORY SCREEN
    // ------------------------------------------------------------------
    private JPanel buildHistoryScreen() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("Transaction History", SwingConstants.CENTER);
        title.setFont(new Font("Tahoma", Font.BOLD, 18));
        title.setForeground(LIGHT_TEXT);

        JLabel columnHeader = new JLabel(HISTORY_COLUMN_HEADER);
        columnHeader.setFont(new Font("Monospaced", Font.BOLD, 12));
        columnHeader.setForeground(LIGHT_TEXT);
        columnHeader.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 0));

        historyListModel = new DefaultListModel<>();
        JList<String> historyList = new JList<>(historyListModel);
        historyList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(historyList);

        JButton backButton = new JButton("Back to Dashboard");
        backButton.addActionListener(e -> showScreen(DASHBOARD_SCREEN));

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setOpaque(false);
        topPanel.add(title, BorderLayout.NORTH);
        topPanel.add(columnHeader, BorderLayout.SOUTH);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(backButton, BorderLayout.SOUTH);

        bindEnterToButton(panel, backButton);

        return wrapWithHeader(panel);
    }

    /** Queries the database for the current user's transactions and refreshes the list on screen. */
    private void refreshHistoryList() {
        historyListModel.clear();
        if (currentUser == null) return;

        try {
            List<Transaction> history = DatabaseHelper.getHistory(currentUser.id);
            if (history.isEmpty()) {
                historyListModel.addElement("No transactions yet.");
            } else {
                for (Transaction t : history) {
                    historyListModel.addElement(t.toString());
                }
            }
        } catch (SQLException ex) {
            historyListModel.addElement("Could not load history: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // TRANSFER FUNDS SCREEN
    // ------------------------------------------------------------------
    private JPanel buildTransferScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Transfer Funds");
        title.setFont(new Font("Tahoma", Font.BOLD, 20));

        JLabel emailLabel = new JLabel("Recipient Email or Mobile:");
        transferEmailField = new JTextField(18);
        firstFieldByScreen.put(TRANSFER_SCREEN, transferEmailField);

        JLabel amountLabel = new JLabel("Amount:");
        transferAmountField = new JTextField(18);

        JButton sendButton = new JButton("Send Transfer");
        JButton backButton = new JButton("Back to Dashboard");

        transferMessageLabel = new JLabel(" ");
        transferMessageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(title, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1; gbc.gridx = 0; panel.add(emailLabel, gbc);
        gbc.gridx = 1; panel.add(transferEmailField, gbc);

        gbc.gridy = 2; gbc.gridx = 0; panel.add(amountLabel, gbc);
        gbc.gridx = 1; panel.add(transferAmountField, gbc);

        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(sendButton, gbc);

        gbc.gridy = 4;
        panel.add(backButton, gbc);

        gbc.gridy = 5;
        panel.add(transferMessageLabel, gbc);

        sendButton.addActionListener(e -> {
            String recipientIdentifier = transferEmailField.getText().trim();

            if (recipientIdentifier.isEmpty()) {
                transferMessageLabel.setForeground(Color.RED);
                transferMessageLabel.setText("Enter a recipient email or mobile number.");
                return;
            }
            boolean isOwnEmail = recipientIdentifier.equalsIgnoreCase(currentUser.email);
            boolean isOwnMobile = currentUser.mobileNumber != null
                    && recipientIdentifier.equals(currentUser.mobileNumber);
            if (isOwnEmail || isOwnMobile) {
                transferMessageLabel.setForeground(Color.RED);
                transferMessageLabel.setText("You can't transfer to your own account.");
                return;
            }

            Double amount = parseAmount(transferAmountField.getText(), transferMessageLabel);
            if (amount == null) return;

            int choice = JOptionPane.showConfirmDialog(panel,
                    "Transfer " + CURRENCY_SYMBOL + String.format("%.2f", amount)
                            + " to " + recipientIdentifier + "?",
                    "Confirm Transfer", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;

            try {
                // DatabaseHelper.transferFunds does the recipient lookup (by
                // email or mobile number), the balance check, both balance
                // updates, and both transaction log entries as one
                // all-or-nothing database transaction.
                DatabaseHelper.transferFunds(currentUser.id, currentUser.email, recipientIdentifier, amount);
                currentUser.balance -= amount;

                transferMessageLabel.setForeground(SUCCESS_GREEN);
                transferMessageLabel.setText("Transferred " + CURRENCY_SYMBOL
                        + String.format("%.2f", amount) + " to " + recipientIdentifier);
                transferEmailField.setText("");
                transferAmountField.setText("");
                refreshDashboard();
            } catch (SQLException ex) {
                transferMessageLabel.setForeground(Color.RED);
                transferMessageLabel.setText(ex.getMessage());
            }
        });

        backButton.addActionListener(e -> {
            transferMessageLabel.setForeground(Color.RED);
            transferMessageLabel.setText(" ");
            showScreen(DASHBOARD_SCREEN);
        });

        bindEnterToButton(panel, sendButton);

        return wrapWithHeader(panel);
    }

    // ------------------------------------------------------------------
    // FORGOT PASSWORD - STEP 1: enter registered email, receive an OTP
    // ------------------------------------------------------------------
    private JPanel buildForgotEmailScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Forgot Password");
        title.setFont(new Font("Tahoma", Font.BOLD, 20));

        JLabel emailLabel = new JLabel("Registered Email or Mobile:");
        forgotEmailField = new JTextField(18);
        firstFieldByScreen.put(FORGOT_EMAIL_SCREEN, forgotEmailField);

        JButton sendButton = new JButton("Send OTP");
        JButton backButton = new JButton("Back to Login");

        forgotEmailMessageLabel = new JLabel(" ");
        forgotEmailMessageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(title, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1; gbc.gridx = 0; panel.add(emailLabel, gbc);
        gbc.gridx = 1; panel.add(forgotEmailField, gbc);

        gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(sendButton, gbc);

        gbc.gridy = 3;
        panel.add(backButton, gbc);

        gbc.gridy = 4;
        panel.add(forgotEmailMessageLabel, gbc);

        sendButton.addActionListener(e -> {
            String identifier = forgotEmailField.getText().trim();
            if (identifier.isEmpty()) {
                forgotEmailMessageLabel.setForeground(Color.RED);
                forgotEmailMessageLabel.setText("Enter your registered email or mobile number.");
                return;
            }
            try {
                if (!DatabaseHelper.emailExists(identifier) && !DatabaseHelper.mobileNumberExists(identifier)) {
                    forgotEmailMessageLabel.setForeground(Color.RED);
                    forgotEmailMessageLabel.setText("No account found with that email or mobile number.");
                    return;
                }
                String otp = DatabaseHelper.generateOtp(identifier);
                forgotEmail = identifier;

                // DEMO NOTE: a real production app would email this code
                // instead of showing it here (see the Security Considerations
                // in the EagleCash design doc). Displaying it directly avoids
                // needing SMTP/email credentials for this school project.
                JOptionPane.showMessageDialog(panel,
                        "Demo Mode: in a real app this code would be emailed to you.\n\n" +
                        "Your OTP is: " + otp + "\n\nIt expires in 5 minutes.",
                        "OTP Sent (Demo)", JOptionPane.INFORMATION_MESSAGE);

                forgotOtpField.setText("");
                forgotOtpMessageLabel.setForeground(Color.RED);
                forgotOtpMessageLabel.setText(" ");
                showScreen(FORGOT_OTP_SCREEN);
            } catch (SQLException ex) {
                forgotEmailMessageLabel.setForeground(Color.RED);
                forgotEmailMessageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        backButton.addActionListener(e -> {
            forgotEmailMessageLabel.setForeground(Color.RED);
            forgotEmailMessageLabel.setText(" ");
            showScreen(LOGIN_SCREEN);
        });

        bindEnterToButton(panel, sendButton);

        return wrapWithHeader(panel);
    }

    // ------------------------------------------------------------------
    // FORGOT PASSWORD - STEP 2: enter and verify the OTP
    // ------------------------------------------------------------------
    private JPanel buildForgotOtpScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Enter OTP");
        title.setFont(new Font("Tahoma", Font.BOLD, 20));

        JLabel instructions = new JLabel("<html><div style='text-align:center;'>" +
                "Enter the 6-digit code shown in the demo popup.</div></html>");
        instructions.setFont(new Font("Tahoma", Font.PLAIN, 12));

        JLabel otpLabel = new JLabel("OTP Code:");
        forgotOtpField = new JTextField(18);
        firstFieldByScreen.put(FORGOT_OTP_SCREEN, forgotOtpField);

        JButton verifyButton = new JButton("Verify OTP");
        JButton backButton = new JButton("Back");

        forgotOtpMessageLabel = new JLabel(" ");
        forgotOtpMessageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(title, gbc);

        gbc.gridy = 1;
        panel.add(instructions, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 2; gbc.gridx = 0; panel.add(otpLabel, gbc);
        gbc.gridx = 1; panel.add(forgotOtpField, gbc);

        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(verifyButton, gbc);

        gbc.gridy = 4;
        panel.add(backButton, gbc);

        gbc.gridy = 5;
        panel.add(forgotOtpMessageLabel, gbc);

        verifyButton.addActionListener(e -> {
            String enteredOtp = forgotOtpField.getText().trim();
            if (enteredOtp.isEmpty()) {
                forgotOtpMessageLabel.setForeground(Color.RED);
                forgotOtpMessageLabel.setText("Enter the OTP code.");
                return;
            }
            try {
                if (!DatabaseHelper.verifyOtp(forgotEmail, enteredOtp)) {
                    forgotOtpMessageLabel.setForeground(Color.RED);
                    forgotOtpMessageLabel.setText("Invalid or expired OTP.");
                    return;
                }
                forgotOtpMessageLabel.setForeground(Color.RED);
                forgotOtpMessageLabel.setText(" ");
                forgotNewPinField.setText("");
                forgotConfirmPinField.setText("");
                forgotResetMessageLabel.setForeground(Color.RED);
                forgotResetMessageLabel.setText(" ");
                showScreen(FORGOT_RESET_SCREEN);
            } catch (SQLException ex) {
                forgotOtpMessageLabel.setForeground(Color.RED);
                forgotOtpMessageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        backButton.addActionListener(e -> {
            forgotOtpMessageLabel.setForeground(Color.RED);
            forgotOtpMessageLabel.setText(" ");
            showScreen(FORGOT_EMAIL_SCREEN);
        });

        bindEnterToButton(panel, verifyButton);

        return wrapWithHeader(panel);
    }

    // ------------------------------------------------------------------
    // FORGOT PASSWORD - STEP 3: set a new PIN
    // ------------------------------------------------------------------
    private JPanel buildForgotResetScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Reset PIN");
        title.setFont(new Font("Tahoma", Font.BOLD, 20));

        JLabel newPinLabel = new JLabel("New PIN (4+ digits):");
        forgotNewPinField = new JPasswordField(18);
        firstFieldByScreen.put(FORGOT_RESET_SCREEN, forgotNewPinField);

        JLabel confirmPinLabel = new JLabel("Confirm PIN:");
        forgotConfirmPinField = new JPasswordField(18);

        JButton resetButton = new JButton("Reset PIN");
        JButton cancelButton = new JButton("Cancel");

        forgotResetMessageLabel = new JLabel(" ");
        forgotResetMessageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(title, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1; gbc.gridx = 0; panel.add(newPinLabel, gbc);
        gbc.gridx = 1; panel.add(forgotNewPinField, gbc);

        gbc.gridy = 2; gbc.gridx = 0; panel.add(confirmPinLabel, gbc);
        gbc.gridx = 1; panel.add(forgotConfirmPinField, gbc);

        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(resetButton, gbc);

        gbc.gridy = 4;
        panel.add(cancelButton, gbc);

        gbc.gridy = 5;
        panel.add(forgotResetMessageLabel, gbc);

        resetButton.addActionListener(e -> {
            String newPin = new String(forgotNewPinField.getPassword());
            String confirmPin = new String(forgotConfirmPinField.getPassword());

            if (!newPin.matches("\\d{4,}")) {
                forgotResetMessageLabel.setForeground(Color.RED);
                forgotResetMessageLabel.setText("PIN must be at least 4 digits.");
                return;
            }
            if (!newPin.equals(confirmPin)) {
                forgotResetMessageLabel.setForeground(Color.RED);
                forgotResetMessageLabel.setText("PINs do not match.");
                return;
            }
            try {
                DatabaseHelper.resetPin(forgotEmail, newPin);
                JOptionPane.showMessageDialog(panel,
                        "Your PIN has been reset. Please log in with your new PIN.",
                        "Success", JOptionPane.INFORMATION_MESSAGE);
                forgotEmail = null;
                forgotNewPinField.setText("");
                forgotConfirmPinField.setText("");
                forgotResetMessageLabel.setForeground(Color.RED);
                forgotResetMessageLabel.setText(" ");
                showScreen(LOGIN_SCREEN);
            } catch (SQLException ex) {
                forgotResetMessageLabel.setForeground(Color.RED);
                forgotResetMessageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        cancelButton.addActionListener(e -> {
            forgotResetMessageLabel.setForeground(Color.RED);
            forgotResetMessageLabel.setText(" ");
            showScreen(LOGIN_SCREEN);
        });

        bindEnterToButton(panel, resetButton);

        return wrapWithHeader(panel);
    }

    // ------------------------------------------------------------------
    // ADMIN LOGIN SCREEN
    // ------------------------------------------------------------------
    private JPanel buildAdminLoginScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Admin Login");
        title.setFont(new Font("Tahoma", Font.BOLD, 20));

        JLabel usernameLabel = new JLabel("Username:");
        adminUsernameField = new JTextField(18);
        firstFieldByScreen.put(ADMIN_LOGIN_SCREEN, adminUsernameField);

        JLabel passwordLabel = new JLabel("Password:");
        JPasswordField adminPasswordField = new JPasswordField(18);

        JButton loginButton = new JButton("Login");
        JButton backButton = new JButton("Back to Login");

        adminLoginMessageLabel = new JLabel(" ");
        adminLoginMessageLabel.setForeground(Color.RED);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        panel.add(title, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1; gbc.gridx = 0; panel.add(usernameLabel, gbc);
        gbc.gridx = 1; panel.add(adminUsernameField, gbc);

        gbc.gridy = 2; gbc.gridx = 0; panel.add(passwordLabel, gbc);
        gbc.gridx = 1; panel.add(adminPasswordField, gbc);

        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(loginButton, gbc);

        gbc.gridy = 4;
        panel.add(backButton, gbc);

        gbc.gridy = 5;
        panel.add(adminLoginMessageLabel, gbc);

        loginButton.addActionListener(e -> {
            String username = adminUsernameField.getText().trim();
            String password = new String(adminPasswordField.getPassword());

            if (username.equals(ADMIN_USERNAME) && password.equals(ADMIN_PASSWORD)) {
                adminLoginMessageLabel.setForeground(Color.RED);
                adminLoginMessageLabel.setText(" ");
                adminUsernameField.setText("");
                adminPasswordField.setText("");
                refreshAdminDashboard();
                showScreen(ADMIN_DASHBOARD_SCREEN);
            } else {
                adminLoginMessageLabel.setForeground(Color.RED);
                adminLoginMessageLabel.setText("Incorrect admin username or password.");
            }
        });

        backButton.addActionListener(e -> {
            adminLoginMessageLabel.setForeground(Color.RED);
            adminLoginMessageLabel.setText(" ");
            showScreen(LOGIN_SCREEN);
        });

        bindEnterToButton(panel, loginButton);

        return wrapWithHeader(panel);
    }

    // ------------------------------------------------------------------
    // ADMIN DASHBOARD SCREEN - monitoring: how many users have registered,
    // plus a list of every account for a closer look.
    // ------------------------------------------------------------------
    private JPanel buildAdminDashboardScreen() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("Admin Dashboard", SwingConstants.CENTER);
        title.setFont(new Font("Tahoma", Font.BOLD, 18));
        title.setForeground(LIGHT_TEXT);

        adminUserCountLabel = new JLabel("Total Registered Users: -", SwingConstants.CENTER);
        adminUserCountLabel.setFont(new Font("Tahoma", Font.BOLD, 16));
        adminUserCountLabel.setForeground(LIGHT_TEXT);

        JLabel columnHeader = new JLabel(ADMIN_COLUMN_HEADER);
        columnHeader.setFont(new Font("Monospaced", Font.BOLD, 12));
        columnHeader.setForeground(LIGHT_TEXT);
        columnHeader.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 0));

        adminUserListModel = new DefaultListModel<>();
        JList<String> adminUserList = new JList<>(adminUserListModel);
        adminUserList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(adminUserList);

        // Double-click a row to view that user's transaction history.
        adminUserList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int index = adminUserList.locationToIndex(e.getPoint());
                if (index < 0 || index >= adminUsersCache.size()) return;
                showUserHistoryDialog(adminUsersCache.get(index));
            }
        });

        JPanel topPanel = new JPanel(new BorderLayout(4, 4));
        topPanel.setOpaque(false);
        topPanel.add(title, BorderLayout.NORTH);
        topPanel.add(adminUserCountLabel, BorderLayout.CENTER);
        topPanel.add(columnHeader, BorderLayout.SOUTH);

        JLabel hintLabel = new JLabel("Tip: double-click a user to view their transaction history.",
                SwingConstants.CENTER);
        hintLabel.setFont(new Font("Tahoma", Font.ITALIC, 11));
        hintLabel.setForeground(LIGHT_TEXT);

        JButton refreshButton = new JButton("Refresh");
        JButton logoutButton = new JButton("Logout");
        // These two buttons sit inside bottomPanel below, not directly inside
        // this screen's panel, so applyNavyTheme's shallow pass over panel's
        // direct children won't reach them - style them explicitly instead.
        flattenButton(refreshButton);
        flattenButton(logoutButton);

        JPanel buttonRow = new JPanel(new GridLayout(1, 2, 8, 0));
        buttonRow.setOpaque(false);
        buttonRow.add(refreshButton);
        buttonRow.add(logoutButton);

        JPanel bottomPanel = new JPanel(new BorderLayout(0, 4));
        bottomPanel.setOpaque(false);
        bottomPanel.add(hintLabel, BorderLayout.NORTH);
        bottomPanel.add(buttonRow, BorderLayout.SOUTH);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        refreshButton.addActionListener(e -> refreshAdminDashboard());

        logoutButton.addActionListener(e -> showScreen(LOGIN_SCREEN));

        bindEnterToButton(panel, refreshButton);

        return wrapWithHeader(panel);
    }

    /** Reloads the registered-user count and the account list from the database. */
    private void refreshAdminDashboard() {
        try {
            int count = DatabaseHelper.getUserCount();
            adminUserCountLabel.setText("Total Registered Users: " + count);

            List<User> users = DatabaseHelper.getAllUsers();
            adminUserListModel.clear();
            adminUsersCache.clear();
            if (users.isEmpty()) {
                adminUserListModel.addElement("No users registered yet.");
            } else {
                for (User u : users) {
                    adminUserListModel.addElement(String.format("#%-4d %-20s %-28s %-14s %s%.2f",
                            u.id, u.name, u.email,
                            u.mobileNumber == null ? "-" : u.mobileNumber,
                            CURRENCY_SYMBOL, u.balance));
                    adminUsersCache.add(u);
                }
            }
        } catch (SQLException ex) {
            adminUserCountLabel.setText("Total Registered Users: (error)");
            adminUserListModel.clear();
            adminUsersCache.clear();
            adminUserListModel.addElement("Could not load users: " + ex.getMessage());
        }
    }

    /**
     * Opens a read-only popup listing every transaction for the given user -
     * lets the admin double-click a row on the Admin Dashboard to look at
     * that customer's transaction history without needing their PIN.
     */
    private void showUserHistoryDialog(User user) {
        DefaultListModel<String> dialogListModel = new DefaultListModel<>();
        try {
            List<Transaction> history = DatabaseHelper.getHistory(user.id);
            if (history.isEmpty()) {
                dialogListModel.addElement("No transactions yet.");
            } else {
                for (Transaction t : history) {
                    dialogListModel.addElement(t.toString());
                }
            }
        } catch (SQLException ex) {
            dialogListModel.addElement("Could not load history: " + ex.getMessage());
        }

        JLabel columnHeader = new JLabel(HISTORY_COLUMN_HEADER);
        columnHeader.setFont(new Font("Monospaced", Font.BOLD, 12));
        columnHeader.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 0));

        JList<String> dialogList = new JList<>(dialogListModel);
        dialogList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane dialogScrollPane = new JScrollPane(dialogList);
        dialogScrollPane.setPreferredSize(new Dimension(480, 260));

        JPanel dialogPanel = new JPanel(new BorderLayout(4, 4));
        dialogPanel.add(columnHeader, BorderLayout.NORTH);
        dialogPanel.add(dialogScrollPane, BorderLayout.CENTER);

        JOptionPane.showMessageDialog(this, dialogPanel,
                "Transaction History - " + user.name + " (" + user.email + ")",
                JOptionPane.PLAIN_MESSAGE);
    }

    /** Parses the amount field, showing an error message if it's invalid. Returns null on failure. */
    private Double parseAmount(String text, JLabel messageLabel) {
        try {
            double amount = Double.parseDouble(text.trim());
            if (amount <= 0) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Enter an amount greater than 0.");
                return null;
            }
            return amount;
        } catch (NumberFormatException ex) {
            messageLabel.setForeground(Color.RED);
            messageLabel.setText("Please enter a valid number.");
            return null;
        }
    }

    /** Updates the dashboard labels to reflect the currently logged-in user. */
    private void refreshDashboard() {
        if (currentUser == null) return;
        welcomeLabel.setText("Welcome, " + currentUser.name + "!");
        balanceLabel.setText("Balance: " + CURRENCY_SYMBOL + String.format("%.2f", currentUser.balance));
    }

    // ------------------------------------------------------------------
    // MAIN - program entry point
    // ------------------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            EagleCashOnlineBankingApp app = new EagleCashOnlineBankingApp();
            app.setVisible(true);
        });
    }
}

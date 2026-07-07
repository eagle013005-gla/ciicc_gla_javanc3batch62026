import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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

    // Screen name constants
    private static final String LOGIN_SCREEN = "LOGIN";
    private static final String REGISTER_SCREEN = "REGISTER";
    private static final String DASHBOARD_SCREEN = "DASHBOARD";
    private static final String HISTORY_SCREEN = "HISTORY";
    private static final String TRANSFER_SCREEN = "TRANSFER";

    private JLabel balanceLabel;
    private JLabel welcomeLabel;
    private DefaultListModel<String> historyListModel;
    private JTextField transferEmailField;
    private JTextField transferAmountField;
    private JLabel transferMessageLabel;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

    // Philippine peso sign (\u20B1 = "PHP"). Written as a unicode escape so it
    // compiles correctly no matter what text encoding your editor/terminal uses.
    private static final String CURRENCY_SYMBOL = "\u20B1";

    public EagleCashOnlineBankingApp() {
        super("EagleCash Online Banking App");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(460, 480);
        setLocationRelativeTo(null);
        setResizable(false);

        DatabaseHelper.initDatabase();

        mainPanel.add(buildLoginScreen(), LOGIN_SCREEN);
        mainPanel.add(buildRegisterScreen(), REGISTER_SCREEN);
        mainPanel.add(buildDashboardScreen(), DASHBOARD_SCREEN);
        mainPanel.add(buildHistoryScreen(), HISTORY_SCREEN);
        mainPanel.add(buildTransferScreen(), TRANSFER_SCREEN);

        add(mainPanel);
        cardLayout.show(mainPanel, LOGIN_SCREEN);
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
     * Header shown at the top of every screen: the eagle logo on the left,
     * with "EagleCash" and the tagline centered vertically beside it.
     */
    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

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
        }
        header.add(logoLabel, BorderLayout.WEST);

        JPanel textPanel = new JPanel();
        textPanel.setOpaque(false);
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

        JLabel appName = new JLabel("EagleCash");
        appName.setFont(new Font("Tahoma", Font.BOLD, 20));
        appName.setAlignmentX(Component.CENTER_ALIGNMENT);

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

        textPanel.add(Box.createVerticalGlue());
        textPanel.add(Box.createVerticalStrut(10)); // nudges "EagleCash" down a bit from the top
        textPanel.add(appName);
        textPanel.add(Box.createVerticalStrut(4));
        textPanel.add(tagline);
        textPanel.add(Box.createVerticalGlue());

        header.add(textPanel, BorderLayout.CENTER);

        return header;
    }

    /** Wraps a screen's content panel with the shared header along the top. */
    private JPanel wrapWithHeader(JPanel content) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(buildHeader(), BorderLayout.NORTH);
        wrapper.add(content, BorderLayout.CENTER);
        return wrapper;
    }

    // ------------------------------------------------------------------
    // Simple data classes
    // ------------------------------------------------------------------
    private static class User {
        int id;
        String name;
        String email;
        String pin;
        double balance;
    }

    private static class Transaction {
        String type;
        double amount;
        String timestamp; // stored as ISO text in the DB
        String targetEmail; // the other party's email, only set for transfers

        @Override
        public String toString() {
            String displayTime = timestamp;
            try {
                displayTime = LocalDateTime.parse(timestamp).format(TIME_FORMAT);
            } catch (Exception ignored) {
                // If parsing fails for any reason, fall back to the raw stored text.
            }

            String sign = (type.equals("Deposit") || type.equals("Transfer Received")) ? "+" : "-";

            String label = type;
            if ("Transfer Sent".equals(type) && targetEmail != null) {
                label = "Transfer to " + targetEmail;
            } else if ("Transfer Received".equals(type) && targetEmail != null) {
                label = "Transfer from " + targetEmail;
            }

            return String.format("%s   %s%s%.2f   %s", displayTime, sign, CURRENCY_SYMBOL, amount, label);
        }
    }

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
                    "  pin TEXT NOT NULL," +
                    "  balance REAL NOT NULL DEFAULT 0" +
                    ")";
            String transactionsTable =
                    "CREATE TABLE IF NOT EXISTS transactions (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  user_id INTEGER NOT NULL," +
                    "  type TEXT NOT NULL," +
                    "  amount REAL NOT NULL," +
                    "  timestamp TEXT NOT NULL," +
                    "  target_email TEXT," +
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

            // Migration: older copies of bank.db created before the Transfer
            // feature existed won't have this column yet. Adding it here means
            // you don't have to delete your existing database to get transfers
            // working. If the column already exists, SQLite throws an error
            // that we simply ignore.
            try (Connection conn = connect();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE transactions ADD COLUMN target_email TEXT");
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

        static void insertUser(String name, String email, String pin) throws SQLException {
            String sql = "INSERT INTO users(name, email, pin, balance) VALUES (?, ?, ?, 0)";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, email);
                ps.setString(3, pin);
                ps.executeUpdate();
            }
        }

        /** Returns the matching user, or null if the email/PIN combination doesn't match. */
        static User login(String email, String pin) throws SQLException {
            String sql = "SELECT id, name, email, pin, balance FROM users WHERE email = ?";
            try (Connection conn = connect();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;           // no account with that email
                    if (!rs.getString("pin").equals(pin)) return null; // wrong PIN

                    User user = new User();
                    user.id = rs.getInt("id");
                    user.name = rs.getString("name");
                    user.email = rs.getString("email");
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
            String sql = "SELECT type, amount, timestamp, target_email FROM transactions " +
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
         */
        static void transferFunds(int fromUserId, String fromEmail, String toEmail, double amount)
                throws SQLException {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false); // start a manual transaction
                try {
                    int toUserId;
                    double toBalance;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id, balance FROM users WHERE email = ?")) {
                        ps.setString(1, toEmail);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new SQLException("No account found with that recipient email.");
                            }
                            toUserId = rs.getInt("id");
                            toBalance = rs.getDouble("balance");
                        }
                    }

                    if (toUserId == fromUserId) {
                        throw new SQLException("You can't transfer money to your own account.");
                    }

                    double fromBalance;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT balance FROM users WHERE id = ?")) {
                        ps.setInt(1, fromUserId);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            fromBalance = rs.getDouble("balance");
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

                    String insertSql = "INSERT INTO transactions(user_id, type, amount, timestamp, target_email) " +
                                        "VALUES (?, ?, ?, ?, ?)";
                    String now = LocalDateTime.now().toString();

                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setInt(1, fromUserId);
                        ps.setString(2, "Transfer Sent");
                        ps.setDouble(3, amount);
                        ps.setString(4, now);
                        ps.setString(5, toEmail);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setInt(1, toUserId);
                        ps.setString(2, "Transfer Received");
                        ps.setDouble(3, amount);
                        ps.setString(4, now);
                        ps.setString(5, fromEmail);
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

        JLabel emailLabel = new JLabel("Email:");
        JTextField emailField = new JTextField(18);

        JLabel pinLabel = new JLabel("PIN:");
        JPasswordField pinField = new JPasswordField(18);

        JButton loginButton = new JButton("Login");
        JButton goRegisterButton = new JButton("Create an account");

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
        panel.add(messageLabel, gbc);

        loginButton.addActionListener(e -> {
            String email = emailField.getText().trim();
            String pin = new String(pinField.getPassword());

            try {
                User user = DatabaseHelper.login(email, pin);
                if (user == null) {
                    messageLabel.setText("Incorrect email or PIN.");
                } else {
                    currentUser = user;
                    messageLabel.setText(" ");
                    emailField.setText("");
                    pinField.setText("");
                    refreshDashboard();
                    cardLayout.show(mainPanel, DASHBOARD_SCREEN);
                }
            } catch (SQLException ex) {
                messageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        goRegisterButton.addActionListener(e -> {
            messageLabel.setText(" ");
            cardLayout.show(mainPanel, REGISTER_SCREEN);
        });

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

        JLabel emailLabel = new JLabel("Email:");
        JTextField emailField = new JTextField(18);

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

        gbc.gridy = 3; gbc.gridx = 0; panel.add(pinLabel, gbc);
        gbc.gridx = 1; panel.add(pinField, gbc);

        gbc.gridy = 4; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(registerButton, gbc);

        gbc.gridy = 5;
        panel.add(backButton, gbc);

        gbc.gridy = 6;
        panel.add(messageLabel, gbc);

        registerButton.addActionListener(e -> {
            String name = nameField.getText().trim();
            String email = emailField.getText().trim();
            String pin = new String(pinField.getPassword());

            if (name.isEmpty() || email.isEmpty() || pin.isEmpty()) {
                messageLabel.setText("Please fill in every field.");
                return;
            }
            if (!email.contains("@") || !email.contains(".")) {
                messageLabel.setText("Please enter a valid email.");
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
                DatabaseHelper.insertUser(name, email, pin);
                messageLabel.setForeground(new Color(0, 128, 0));
                messageLabel.setText("Account created! You can log in now.");
                nameField.setText("");
                emailField.setText("");
                pinField.setText("");
            } catch (SQLException ex) {
                messageLabel.setForeground(Color.RED);
                messageLabel.setText("Database error: " + ex.getMessage());
            }
        });

        backButton.addActionListener(e -> {
            messageLabel.setForeground(Color.RED);
            messageLabel.setText(" ");
            cardLayout.show(mainPanel, LOGIN_SCREEN);
        });

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
            try {
                double newBalance = currentUser.balance + amount;
                DatabaseHelper.updateBalance(currentUser.id, newBalance);
                DatabaseHelper.insertTransaction(currentUser.id, "Deposit", amount);
                currentUser.balance = newBalance;
                messageLabel.setForeground(new Color(0, 128, 0));
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
            try {
                double newBalance = currentUser.balance - amount;
                DatabaseHelper.updateBalance(currentUser.id, newBalance);
                DatabaseHelper.insertTransaction(currentUser.id, "Withdraw", amount);
                currentUser.balance = newBalance;
                messageLabel.setForeground(new Color(0, 128, 0));
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
            cardLayout.show(mainPanel, TRANSFER_SCREEN);
        });

        historyButton.addActionListener(e -> {
            refreshHistoryList();
            cardLayout.show(mainPanel, HISTORY_SCREEN);
        });

        logoutButton.addActionListener(e -> {
            currentUser = null;
            messageLabel.setForeground(Color.RED);
            messageLabel.setText(" ");
            amountField.setText("");
            cardLayout.show(mainPanel, LOGIN_SCREEN);
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

        historyListModel = new DefaultListModel<>();
        JList<String> historyList = new JList<>(historyListModel);
        historyList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(historyList);

        JButton backButton = new JButton("Back to Dashboard");
        backButton.addActionListener(e -> cardLayout.show(mainPanel, DASHBOARD_SCREEN));

        panel.add(title, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(backButton, BorderLayout.SOUTH);

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

        JLabel emailLabel = new JLabel("Recipient Email:");
        transferEmailField = new JTextField(18);

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
            String recipientEmail = transferEmailField.getText().trim();

            if (recipientEmail.isEmpty()) {
                transferMessageLabel.setForeground(Color.RED);
                transferMessageLabel.setText("Enter a recipient email.");
                return;
            }
            if (recipientEmail.equalsIgnoreCase(currentUser.email)) {
                transferMessageLabel.setForeground(Color.RED);
                transferMessageLabel.setText("You can't transfer to your own account.");
                return;
            }

            Double amount = parseAmount(transferAmountField.getText(), transferMessageLabel);
            if (amount == null) return;

            try {
                // DatabaseHelper.transferFunds does the recipient lookup, the
                // balance check, both balance updates, and both transaction
                // log entries as one all-or-nothing database transaction.
                DatabaseHelper.transferFunds(currentUser.id, currentUser.email, recipientEmail, amount);
                currentUser.balance -= amount;

                transferMessageLabel.setForeground(new Color(0, 128, 0));
                transferMessageLabel.setText("Transferred " + CURRENCY_SYMBOL
                        + String.format("%.2f", amount) + " to " + recipientEmail);
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
            cardLayout.show(mainPanel, DASHBOARD_SCREEN);
        });

        return wrapWithHeader(panel);
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

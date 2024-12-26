/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~V000.01.06-V000.01.04-V000.00.01-V000.00.00-
 */
package us.bringardner.io.filesource.sftp;
import static us.bringardner.io.filesource.sftp.SftpFileSourceFactory.DEFAULT_PORT;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.Properties;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import us.bringardner.io.filesource.FactoryPropertiesDialog;
import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceChooserDialog;
import us.bringardner.io.filesource.FileSourceFactory;
import us.bringardner.io.filesource.IConnectionPropertiesEditor;

public class SftpPropertyEditPanel extends JPanel implements IConnectionPropertiesEditor {

	private static final long serialVersionUID = 1L;
	private enum AthType {Password,PrivateKey,IdentityFile}
	
	private JTextField userTextField;
	private JPasswordField passwordField;
	private final ButtonGroup buttonGroup = new ButtonGroup();
	private JTextField fileNameTextField;
	private JTextField hostTextField;
	private JTextField portTextField;
	private JRadioButton privateKeyRadioButton;
	private JRadioButton identityFileRadioButton;
	private JRadioButton passwordRadioButton;
	private JPanel passwordPanel;
	private JPanel fileNamePanel;
	private JTextArea privateKeyTextArea;
	private JPanel userPanel;
	private JPanel hostPortPanel;
	private JScrollPane scrollPane;
	
	public static void main(String args[] ) throws InterruptedException {
		FactoryPropertiesDialog dialog = new FactoryPropertiesDialog();
		
		dialog.showDialog();
		FileSourceFactory nf = dialog.getFactory();
		if( nf != null && nf.isConnected()) {
			try {
				FileSource[] roots = nf.listRoots();
				System.out.println("root list sz="+roots.length);
				for(FileSource f : roots) {
					System.out.println("\t"+f);
				}
			} catch (IOException e) {
				// Not implemented
				e.printStackTrace();
			}
		} else {
			System.out.println("Not connected");
		}
		
	}
	
	
	/**
	 * Create the panel.
	 */
	public SftpPropertyEditPanel() {
		
		setLayout(new BorderLayout());
		
		setPreferredSize(new Dimension(780, 400));
		
		//setBounds(0, 0, 752, 934);
		JPanel northPanel = new JPanel();
		//northPanel.setBounds(0, 0, 752, 934);
		//northPanel.setPreferredSize(new Dimension(800, 500));
		add(northPanel,BorderLayout.NORTH);
		northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
		
		
		userPanel = new JPanel();
		userPanel.setPreferredSize(new Dimension(752,50));
		FlowLayout flowLayout = (FlowLayout) userPanel.getLayout();
		flowLayout.setAlignment(FlowLayout.LEFT);
		northPanel.add(userPanel);
		
		JLabel lblNewLabel = new JLabel("User: ");
		userPanel.add(lblNewLabel);
		
		userTextField = new JTextField();
		userPanel.add(userTextField);
		userTextField.setText("tony-tony");
		userTextField.setColumns(10);
		
		hostPortPanel = new JPanel();
		FlowLayout flowLayout_2 = (FlowLayout) hostPortPanel.getLayout();
		flowLayout_2.setAlignment(FlowLayout.LEFT);
		northPanel.add(hostPortPanel);
		
		JLabel lblNewLabel_3 = new JLabel("Host: ");
		hostPortPanel.add(lblNewLabel_3);
		
		hostTextField = new JTextField();
		hostPortPanel.add(hostTextField);
		hostTextField.setText("ec2-54-69-95-39.us-west-2.compute.amazonaws.com");
		hostTextField.setColumns(30);
		
		JLabel lblNewLabel_4 = new JLabel("Port");
		hostPortPanel.add(lblNewLabel_4);
		
		portTextField = new JTextField();
		hostPortPanel.add(portTextField);
		portTextField.setText(""+DEFAULT_PORT);
		portTextField.setColumns(10);
		
		JPanel authTypePanel = new JPanel();
		FlowLayout flowLayout_1 = (FlowLayout) authTypePanel.getLayout();
		flowLayout_1.setAlignment(FlowLayout.LEFT);
		authTypePanel.setBorder(new TitledBorder(null, "Authentication", TitledBorder.CENTER, TitledBorder.TOP, null, null));
		northPanel.add(authTypePanel);
		
		passwordRadioButton = new JRadioButton("Password");
		passwordRadioButton.addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent e) {
				actionAuthTypeChanged();
			}
		});
		buttonGroup.add(passwordRadioButton);
		
		authTypePanel.add(passwordRadioButton);
		
		identityFileRadioButton = new JRadioButton("Private Key File");
		identityFileRadioButton.addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent e) {
				actionAuthTypeChanged();
			}
		});
		identityFileRadioButton.setSelected(true);
		buttonGroup.add(identityFileRadioButton);
		authTypePanel.add(identityFileRadioButton);
		
		privateKeyRadioButton = new JRadioButton("Private Key");
		privateKeyRadioButton.addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent e) {
				actionAuthTypeChanged();
			}
		});
		buttonGroup.add(privateKeyRadioButton);
		authTypePanel.add(privateKeyRadioButton);
		
		passwordPanel = new JPanel();
		northPanel.add(passwordPanel);
		passwordPanel.setVisible(false);
		passwordPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
		
		JLabel lblNewLabel_1 = new JLabel("Password: ");
		passwordPanel.add(lblNewLabel_1);
		
		passwordField = new JPasswordField();
		passwordField.setColumns(20);
		passwordPanel.add(passwordField);
		
		fileNamePanel = new JPanel();
		fileNamePanel.setVisible(false);
		northPanel.add(fileNamePanel);
		fileNamePanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
		
		JLabel lblNewLabel_2 = new JLabel("Private Key File Name: ");
		fileNamePanel.add(lblNewLabel_2);
		
		fileNameTextField = new JTextField();
		fileNamePanel.add(fileNameTextField);
		fileNameTextField.setColumns(30);
		
		JButton btnNewButton = new JButton("Browse");
		btnNewButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				try {
					actionBrowse();
				} catch (IOException e1) {
					JOptionPane.showMessageDialog(SftpPropertyEditPanel.this, e1, "", JOptionPane.ERROR_MESSAGE);
				}
			}
		});
		fileNamePanel.add(btnNewButton);

		scrollPane =  new JScrollPane();
		scrollPane.setVisible(false);
		add(scrollPane, BorderLayout.CENTER);
		
		privateKeyTextArea = new JTextArea();
		//privateKeyTextArea.setVisible(false);
		//privateKeyTextArea.setPreferredSize(new Dimension(750, 500));
		privateKeyTextArea.setText("-----BEGIN RSA PRIVATE KEY-----\r\nMIIEpAIBAAKCAQEAhxhCfFGokUKV83dvyKp66IzjVu9oIByGMLJ9ITsKV9qMBHXCVNK7hq4gy1lE\r\nIRt/X4I5xVTah4+LeMj5qkI20SDhQMTsO+OqDW2gjeomc63PccBznMwJwTQctLWr+0tk5vd7xlyj\r\nussX53UNCRIaEbnYxSLXlkd2S+Gc+52C1tJa1+thmLH/aGjrlY4f2YyBGMH7uI/rtqofP5nxZExT\r\nS+mPVqKIyubHZt9TkdjdhLbLUyIRNTtLGBusWznpSYL1an/MQT2o08G4jF76lGQ/35zDg7raXBZe\r\n8NrJHiaGoc4dzexupzLtOnaEsQfhTbDG6ZCZ+0tk/wNUkJeNrutc1QIDAQABAoIBAEQxkM4kgkzh\r\nKcR+k+TdebGN/OxTaWJcQ7itQNDXdr8mSOuvbetXfOXdXByJ8QQtVzylBfiAftdTNHpCKRUy22zx\r\nhgMl5IHOyHaC0jsQ6VwXbtHi7flGXd4zKhJmamwtgL++SbK17MhL4MMrqOrdQl+USsIodl8br7Fa\r\nL94rgnJhfTzpA4aSwuoM2WB7zngrCbmsQw9mDFwfEOw9J2XireCHW1eSUH7WTCCcnBuM1JyRXaq5\r\nKjnLiLGD13QCzyLrzs7AuaZIdT425hZqIovi4aXawKvg1MombIIzlw8m4eoqIq3LbMHWblStWoiZ\r\n3Aeq/3d8vgBwRhBz3lyzoti7Q7kCgYEAvvXJaEj5CEBmj791w8jtsjo8yJav5yQTVjcdA7pfE81M\r\n33Ns7vVczdi8HyxDV7Q2kzdneiLnJ6Q0U2Ald7riluLOToFRhC4jupbrV5s5p9bhhVnJV3JhRcxB\r\nP/+3qYg3AqWOTekDJQMoYyA+FdAgP6lTaDTyvuJZ1r2AHJon0UcCgYEAtRt02SN48TznkrDdUvr0\r\n78xR35OG/27bliqo8mg2TMOk4A8ua1FYrJEuQ9NqN7lFQEt30PpbbJpb8QZkK66UGSy1ntDKjAK1\r\nWBHFt7JJnN9j23rEPKQhWkOZSzDdwuu3iejRz9oY7+uwQmxGlB5YD4EF2o3yc86dFp8e75mpTwMC\r\ngYAIwMNmoFGp6ynIVQJU4xTiIoE+wIl3ktPAE+6kiRpqkfKAG45WtbB3TwPwedrsXjpSLSv1ETx/\r\nOKudVr7g6hQQznyeZJcT8/l3SAupjFfsNZFIx4DPHVMQG/ixskr83l2HJYeMUq3uOGLViFjQLyYL\r\nRPupvyORVFbB3RXOOdKxaQKBgQCV2ObgdqIt7/em/uHRM8WP151ygK01EbNsV1W8ZA9xinsTzFva\r\n/c6B0gnWosmC26952DeF2G/mtv0VuvUM04DEJ6MKibTdDayf9uyB5mlT+92yjqxphF/4QHBIr2D9\r\nU21kFRfsg4cYlAkdnFr1WPoBsf526/XMbgq52eSN2LUmowKBgQCuYTmnct3BiyRYFdWXmGd+Bp+7\r\nghMxqTK+0wL46cVSqUJYnvxv8Ik6Y53D+T78niqTSnf6JM8pV/lcOiD7TkvjYTLg9UAu/P0Le3JJ\r\nnrRZMr/479GPPtX9KnACLoSxrnRTzwJhF5eK9Ay61OxM7np8SRljNeTySGZeiDvVrzG6Fw==\r\n-----END RSA PRIVATE KEY-----");
		privateKeyTextArea.setToolTipText("");
		
		scrollPane.setViewportView(privateKeyTextArea);
		scrollPane.setVisible(false);
		
		passwordRadioButton.setSelected(true);
		
	}

	protected void actionAuthTypeChanged() {
		if( passwordRadioButton == null || identityFileRadioButton == null || privateKeyRadioButton == null) {
			return;
		}
		scrollPane.setVisible(false);
		passwordPanel.setVisible(false);
		fileNamePanel.setVisible(false);
		
		AthType type = passwordRadioButton.isSelected()?AthType.Password:
			privateKeyRadioButton.isSelected()?AthType.PrivateKey:
				AthType.IdentityFile;
		
		
		switch (type) {
		case Password:
			passwordPanel.setVisible(true);
			break;
		case IdentityFile:
			fileNamePanel.setVisible(true);
			break;
		case PrivateKey:
			scrollPane.setVisible(true);
			break;

		default:
			break;
		}
		
		
	}

	protected void actionBrowse() throws IOException {
		FileSourceChooserDialog fc = new FileSourceChooserDialog();
		String fileName = fileNameTextField.getText();
		if( !fileName.isEmpty()) {
			fc.setSelectedFile(FileSourceFactory.getDefaultFactory().createFileSource(fileName));
		}
		fc.setFileSelectionMode(FileSourceChooserDialog.FILES_ONLY);
		
		if(fc.showOpenDialog(this)==FileSourceChooserDialog.APPROVE_OPTION) {
			FileSource file = fc.getSelectedFile();
			fileNameTextField.setText(file.getCanonicalPath());
		}
	}

	@Override
	public void setProperties(Properties p) {
		userTextField.setText(p.getProperty(SftpFileSourceFactory.PROP_USER,""));
		hostTextField.setText(p.getProperty(SftpFileSourceFactory.PROP_HOST,""));
		portTextField.setText(p.getProperty(SftpFileSourceFactory.PROP_PORT,""));
		passwordField.setText(p.getProperty(SftpFileSourceFactory.PROP_PASSWORD,""));
		fileNameTextField.setText(p.getProperty(SftpFileSourceFactory.PROP_PRIVATE_KEY_FILE_NAME,""));
		privateKeyTextArea.setText(p.getProperty(SftpFileSourceFactory.PROP_PRIVATE_KEY,""));
	}

	@Override
	public Properties getProperties() {
		Properties ret = new Properties();
		ret.setProperty(SftpFileSourceFactory.PROP_USER, userTextField.getText());
		ret.setProperty(SftpFileSourceFactory.PROP_HOST, hostTextField.getText());
		ret.setProperty(SftpFileSourceFactory.PROP_PORT, portTextField.getText());
		ret.setProperty(SftpFileSourceFactory.PROP_PASSWORD, new String(passwordField.getPassword()));
		ret.setProperty(SftpFileSourceFactory.PROP_PRIVATE_KEY_FILE_NAME, fileNameTextField.getText());
		ret.setProperty(SftpFileSourceFactory.PROP_PRIVATE_KEY, privateKeyTextArea.getText());

		return ret;
	}

	
}

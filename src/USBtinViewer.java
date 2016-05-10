/*
 * USBtinViewer - Simple GUI for USBtin - USB to CAN interface
 * http://www.fischl.de/usbtin
 *
 * Notes:
 * - The timestamp is generated in the application on the host, the hardware
 *   timestamping is currently not used!
 * - Disable "Follow" on high-loaded busses!
 *
 * Copyright (C) 2014-2016  Thomas Fischl 
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import de.fischl.usbtin.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import jssc.SerialPortList;
import java.util.prefs.*;

import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static java.awt.Toolkit.getDefaultToolkit;
import static java.lang.System.getProperty;

/**
 * Main window frame for USBtinViewer
 * 
 * @author Thomas Fischl
 */
public class USBtinViewer extends javax.swing.JFrame implements CANMessageListener {

    /** Version string */
    protected final String version = "1.3";

    /** USBtin device */
    protected USBtin usbtin = new USBtin();
    
    /** Input fields containing payload data */    
    protected JTextField[] msgDataFields;
    
    /** True, if converting between message string <-> input fields in process */
    protected boolean disableMsgUpdate = false;
    
    /** Start timestamp in system-milliseconds */
    protected long baseTimestamp = 0;

    /**
     * Creates new form and initialize it
     */
    public USBtinViewer() {
        
        // init view components
        initComponents();
        setTitle(getTitle() + " " + version);
        setIconImage(new ImageIcon(getClass().getResource("/res/icons/usbtinviewer.png")).getImage());
        openmodeComboBox.setSelectedItem(USBtin.OpenMode.ACTIVE);
        
        // initialize message payload input fields and add listeners
        msgDataFields = new JTextField[]{msgData0, msgData1, msgData2, msgData3, msgData4, msgData5, msgData6, msgData7};
        DocumentListener msgListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent de) {
                msgFields2msgString();
            }

            @Override
            public void removeUpdate(DocumentEvent de) {
                msgFields2msgString();
            }

            @Override
            public void changedUpdate(DocumentEvent de) {
                msgFields2msgString();
            }
        };
        msgId.getDocument().addDocumentListener(msgListener);
        for (JTextField msgDataField : msgDataFields) {
            msgDataField.getDocument().addDocumentListener(msgListener);
        }

        // add listener to message string input field
        sendMessage.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void insertUpdate(DocumentEvent de) {
                msgString2msgFields();
            }

            @Override
            public void removeUpdate(DocumentEvent de) {
                msgString2msgFields();
            }

            @Override
            public void changedUpdate(DocumentEvent de) {
                msgString2msgFields();
            }
        });

        logTable.addMouseListener(new MouseAdapter() {
            private boolean isIoType(LogMessage message) {
                LogMessage.MessageType type = message.getType();
                return (type == LogMessage.MessageType.OUT ||
                        type == LogMessage.MessageType.IN);
            }

            // "Popup menus are triggered differently on different systems.
            // Therefore, isPopupTrigger should be checked in both
            // mousePressed and mouseReleased"
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    mouseReleased(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    final LogMessageTableModel model = (LogMessageTableModel) logTable.getModel();
                    JPopupMenu popup = new JPopupMenu();

                    popup.add(new AbstractAction("Resend") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            for (int r : logTable.getSelectedRows()) {
                                LogMessage message = model.getMessage(r);
                                if (isIoType(message)) {
                                    send(message.getCanmsg());
                                }
                            }
                        }
                    });

                    popup.add(new AbstractAction("Copy") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            StringBuilder builder = new StringBuilder();
                            String separator = getProperty("line.separator");

                            for (int r : logTable.getSelectedRows()) {
                                LogMessage message = model.getMessage(r);
                                if (isIoType(message)) {
                                    builder.append(message.getCanmsg().toString());
                                    builder.append(separator);
                                }
                            }

                            getDefaultToolkit()
                                    .getSystemClipboard()
                                    .setContents(new StringSelection(builder.toString()), null);
                        }
                    });

                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        // configure table columns
        TableColumnModel columnModel = logTable.getColumnModel();
        columnModel.getColumn(0).setPreferredWidth(100);
        columnModel.getColumn(1).setPreferredWidth(40);
        columnModel.getColumn(2).setPreferredWidth(90);
        columnModel.getColumn(3).setPreferredWidth(40);
        columnModel.getColumn(4).setPreferredWidth(370);
        
        // set alignment of id column
        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(DefaultTableCellRenderer.RIGHT);
        columnModel.getColumn(2).setCellRenderer(rightRenderer);

        // set alignment of length column
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(DefaultTableCellRenderer.CENTER);
        columnModel.getColumn(3).setCellRenderer(centerRenderer);

        // monitor table
        columnModel = monitorTable.getColumnModel();
        columnModel.getColumn(0).setPreferredWidth(50);
        columnModel.getColumn(1).setPreferredWidth(50);
        columnModel.getColumn(2).setPreferredWidth(40);
        columnModel.getColumn(3).setPreferredWidth(90);
        columnModel.getColumn(4).setPreferredWidth(40);
        columnModel.getColumn(5).setPreferredWidth(370);        
        columnModel.getColumn(3).setCellRenderer(rightRenderer);
        columnModel.getColumn(4).setCellRenderer(centerRenderer);
        
        // trigger initial sync between message string and message input fields
        msgString2msgFields();
        
        // init message listener
        usbtin.addMessageListener(this);
        
        /*RETRIEVE FROM PREFERENCES THE LAST COM PORT NUMBER AND SPEED*/
        // Retrieve the user preference node for the package com.mycompany


        // Get the value of the preference;
        // default value is returned if the preference does not exist
        Preferences prefs = Preferences.userNodeForPackage(USBtinViewer.class);
        String defaultValue = "COM1";
        String propertyValue = prefs.get("COM_PORT", defaultValue);
        serialPort.setSelectedItem(propertyValue);
        
        defaultValue="10000";
        propertyValue = prefs.get("CAN_SPEED", defaultValue);
        bitRate.setSelectedItem(propertyValue);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        serialPort = new javax.swing.JComboBox();
        bitRate = new javax.swing.JComboBox();
        connectionButton = new javax.swing.JButton();
        sendMessage = new javax.swing.JTextField();
        sendButton = new javax.swing.JButton();
        followButton = new javax.swing.JToggleButton();
        openmodeComboBox = new javax.swing.JComboBox();
        clearButton = new javax.swing.JButton();
        msgId = new javax.swing.JTextField();
        msgLength = new javax.swing.JSpinner();
        msgData0 = new javax.swing.JTextField();
        msgData1 = new javax.swing.JTextField();
        msgData2 = new javax.swing.JTextField();
        msgData3 = new javax.swing.JTextField();
        msgData4 = new javax.swing.JTextField();
        msgData5 = new javax.swing.JTextField();
        msgData6 = new javax.swing.JTextField();
        msgData7 = new javax.swing.JTextField();
        msgExt = new javax.swing.JCheckBox();
        msgRTR = new javax.swing.JCheckBox();
        mainTabbedPane = new javax.swing.JTabbedPane();
        logScrollPane = new javax.swing.JScrollPane();
        logTable = new javax.swing.JTable();
        monitorScrollPane = new javax.swing.JScrollPane();
        monitorTable = new javax.swing.JTable();
		ChartPane = new javax.swing.JPanel();
        idField = new javax.swing.JTextField();
        jLabel1 = new javax.swing.JLabel();
        canChart = new CANChart();
        jLabel3 = new javax.swing.JLabel();
        graphLengthTextField = new javax.swing.JTextField();
        addIDButton = new javax.swing.JButton();
        removeIDButton = new javax.swing.JButton();
        jLabel2 = new javax.swing.JLabel();
        typeComboBox = new javax.swing.JComboBox<>();
        jLabel4 = new javax.swing.JLabel();
        offsetComboBox = new javax.swing.JComboBox<>();
        signedCheckBox = new javax.swing.JCheckBox();
        littleEndianCheckBox = new javax.swing.JCheckBox();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("USBtinViewer");

        serialPort.setEditable(true);
        serialPort.setModel(new javax.swing.DefaultComboBoxModel(SerialPortList.getPortNames()));
        serialPort.setToolTipText("Port");

        bitRate.setEditable(true);
        bitRate.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "10000", "20000", "50000", "100000", "125000", "250000", "500000", "800000", "1000000" }));
        bitRate.setToolTipText("Baudrate");

        connectionButton.setText("Connect");
        connectionButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                connectionButtonActionPerformed(evt);
            }
        });

        sendMessage.setText("t00181122334455667788");
        sendMessage.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                sendMessageActionPerformed(evt);
            }
        });

        sendButton.setText("Send");
        sendButton.setEnabled(false);
        sendButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                sendButtonActionPerformed(evt);
            }
        });

        followButton.setSelected(true);
        followButton.setText("Follow");

        openmodeComboBox.setModel(new DefaultComboBoxModel(USBtin.OpenMode.values()));
        openmodeComboBox.setToolTipText("Mode");

        clearButton.setText("Clear");
        clearButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                clearButtonActionPerformed(evt);
            }
        });

        msgId.setColumns(8);
        msgId.setText("001");

        msgLength.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
        msgLength.setModel(new javax.swing.SpinnerNumberModel(0, 0, 8, 1));
        msgLength.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                msgLengthStateChanged(evt);
            }
        });

        msgData0.setColumns(2);
        msgData0.setText("00");
        msgData0.setEnabled(false);

        msgData1.setColumns(2);
        msgData1.setText("00");
        msgData1.setEnabled(false);

        msgData2.setColumns(2);
        msgData2.setText("00");
        msgData2.setEnabled(false);

        msgData3.setColumns(2);
        msgData3.setText("00");
        msgData3.setEnabled(false);

        msgData4.setColumns(2);
        msgData4.setText("00");
        msgData4.setEnabled(false);

        msgData5.setColumns(2);
        msgData5.setText("00");
        msgData5.setEnabled(false);

        msgData6.setColumns(2);
        msgData6.setText("00");
        msgData6.setEnabled(false);

        msgData7.setColumns(2);
        msgData7.setText("00");
        msgData7.setEnabled(false);

        msgExt.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
        msgExt.setText("Ext");
        msgExt.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                msgExtActionPerformed(evt);
            }
        });

        msgRTR.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
        msgRTR.setText("RTR");
        msgRTR.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                msgRTRActionPerformed(evt);
            }
        });

        logTable.setModel(new LogMessageTableModel());
        logScrollPane.setViewportView(logTable);

        mainTabbedPane.addTab("Trace", logScrollPane);

        monitorTable.setModel(new MonitorMessageTableModel());
        monitorScrollPane.setViewportView(monitorTable);

        mainTabbedPane.addTab("Monitor", monitorScrollPane);

		idField.setText("0");
        idField.setToolTipText("");

        jLabel1.setText("ID");

        javax.swing.GroupLayout canChartLayout = new javax.swing.GroupLayout(canChart);
        canChart.setLayout(canChartLayout);
        canChartLayout.setHorizontalGroup(
            canChartLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 0, Short.MAX_VALUE)
        );
        canChartLayout.setVerticalGroup(
            canChartLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 277, Short.MAX_VALUE)
        );

        jLabel3.setText("Length");

        graphLengthTextField.setText("1000");
        graphLengthTextField.setToolTipText("");
        graphLengthTextField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                graphLengthTextFieldActionPerformed(evt);
            }
        });

        addIDButton.setLabel("Add");
        addIDButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addIDButtonActionPerformed(evt);
            }
        });

        removeIDButton.setLabel("Remove");
        removeIDButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                removeIDButtonActionPerformed(evt);
            }
        });

        jLabel2.setText("Offset");

        typeComboBox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Byte", "Word", "Integer", "Long" }));
        typeComboBox.setToolTipText("");

        jLabel4.setText("Type");

        offsetComboBox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "0", "1", "2", "3", "4", "5", "6", "7" }));

        signedCheckBox.setText("Signed");

        littleEndianCheckBox.setText("Little Endian");
        littleEndianCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                littleEndianCheckBoxActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout ChartPaneLayout = new javax.swing.GroupLayout(ChartPane);
        ChartPane.setLayout(ChartPaneLayout);
        ChartPaneLayout.setHorizontalGroup(
            ChartPaneLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(ChartPaneLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(ChartPaneLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(ChartPaneLayout.createSequentialGroup()
                        .addGroup(ChartPaneLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(idField, javax.swing.GroupLayout.PREFERRED_SIZE, 37, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(jLabel1))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(ChartPaneLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(ChartPaneLayout.createSequentialGroup()
                                .addComponent(jLabel2)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(jLabel4)
                                .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(ChartPaneLayout.createSequentialGroup()
                                .addGap(2, 2, 2)
                                .addComponent(offsetComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(typeComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(signedCheckBox)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(littleEndianCheckBox)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(addIDButton)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(removeIDButton)))
                        .addGap(18, 18, 18)
                        .addGroup(ChartPaneLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(graphLengthTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(jLabel3)))
                    .addComponent(canChart, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        ChartPaneLayout.setVerticalGroup(
            ChartPaneLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(ChartPaneLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(ChartPaneLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel1)
                    .addComponent(jLabel3)
                    .addComponent(jLabel2)
                    .addComponent(jLabel4))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(ChartPaneLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(idField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(graphLengthTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(addIDButton)
                    .addComponent(removeIDButton)
                    .addComponent(typeComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(offsetComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(signedCheckBox)
                    .addComponent(littleEndianCheckBox))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(canChart, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );

        mainTabbedPane.addTab("Graph", ChartPane);
		
        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(mainTabbedPane)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(serialPort, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(bitRate, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(openmodeComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(connectionButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(clearButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(followButton))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(msgId, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(msgLength, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(msgData0, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(msgData1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(msgData2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(msgData3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(msgData4, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(msgData5, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(msgData6, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(msgData7, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(18, 18, 18)
                                .addComponent(msgExt)
                                .addGap(18, 18, 18)
                                .addComponent(msgRTR)
                                .addGap(0, 59, Short.MAX_VALUE))
                            .addComponent(sendMessage))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(sendButton)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(serialPort, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(bitRate, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(openmodeComboBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(connectionButton)
                    .addComponent(followButton)
                    .addComponent(clearButton))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(mainTabbedPane, javax.swing.GroupLayout.DEFAULT_SIZE, 405, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(msgId, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(msgLength, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(msgData0, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(msgData1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(msgData2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(msgData3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(msgData4, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(msgData5, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(msgData6, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(msgData7, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(msgExt)
                    .addComponent(msgRTR))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(sendMessage, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(sendButton))
                .addContainerGap())
        );

		mainTabbedPane.getAccessibleContext().setAccessibleName("Log");
        pack();
    }// </editor-fold>//GEN-END:initComponents

    /**
     * Handle connect/disconnect button action
     * @param evt Action event
     */
    private void connectionButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_connectionButtonActionPerformed

        if (connectionButton.getText().equals("Disconnect")) {
            try {
                usbtin.closeCANChannel();
                usbtin.disconnect();
                log("Disconnected", LogMessage.MessageType.INFO);
            } catch (USBtinException e) {
                log(e.getMessage(), LogMessage.MessageType.ERROR);
            }
            connectionButton.setText("Connect");
            bitRate.setEnabled(true);
            serialPort.setEnabled(true);
            sendButton.setEnabled(false);
            openmodeComboBox.setEnabled(true);
        } else {
            try {
                usbtin.connect((String) serialPort.getSelectedItem());
                usbtin.openCANChannel(Integer.parseInt((String) bitRate.getSelectedItem()), (USBtin.OpenMode) openmodeComboBox.getSelectedItem());
                connectionButton.setText("Disconnect");
                bitRate.setEnabled(false);
                serialPort.setEnabled(false);
                sendButton.setEnabled(true);
                openmodeComboBox.setEnabled(false);
                log("Connected to USBtin (FW" + usbtin.getFirmwareVersion() + "/HW" + usbtin.getHardwareVersion() + ", SN: " + usbtin.getSerialNumber() + ")", LogMessage.MessageType.INFO);

				/*SAVE COM PORT AND SPEED PREFERENCES*/
                Preferences prefs = Preferences.userNodeForPackage(USBtinViewer.class);
                prefs.put("COM_PORT", (String)serialPort.getSelectedItem());
                prefs.put("CAN_SPEED", (String)bitRate.getSelectedItem());
                
                if (baseTimestamp == 0) {
                    baseTimestamp = System.currentTimeMillis();
                }
            } catch (USBtinException e) {
                log(e.getMessage(), LogMessage.MessageType.ERROR);
            }
        }
    }//GEN-LAST:event_connectionButtonActionPerformed

    /**
     * Handle send button event
     * 
     * @param evt Action event
     */
    private void sendButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_sendButtonActionPerformed
        send();
    }//GEN-LAST:event_sendButtonActionPerformed

    /**
     * Handle clear button event
     * 
     * @param evt Action event
     */
    private void clearButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_clearButtonActionPerformed
        LogMessageTableModel tm = (LogMessageTableModel) logTable.getModel();
        tm.clear();
        baseTimestamp = System.currentTimeMillis();
        
        MonitorMessageTableModel mtm = (MonitorMessageTableModel) monitorTable.getModel();
        mtm.clear();
    }//GEN-LAST:event_clearButtonActionPerformed

    /**
     * Handle message length input field change event
     * 
     * @param evt Change event
     */
    private void msgLengthStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_msgLengthStateChanged
        msgFields2msgString();
    }//GEN-LAST:event_msgLengthStateChanged

    /**
     * Handle message extended checkbox event
     * 
     * @param evt Action event
     */
    private void msgExtActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_msgExtActionPerformed
        msgFields2msgString();
    }//GEN-LAST:event_msgExtActionPerformed

    /**
     * Handle message RTR checkbox event
     * 
     * @param evt Action event
     */
    private void msgRTRActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_msgRTRActionPerformed
        msgFields2msgString();
    }//GEN-LAST:event_msgRTRActionPerformed

    /**
     * Handle message string action event
     * 
     * @param evt Action event
     */
    private void sendMessageActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_sendMessageActionPerformed
        if (sendButton.isEnabled()) {
            send();
        }
    }//GEN-LAST:event_sendMessageActionPerformed

	private void graphLengthTextFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_graphLengthTextFieldActionPerformed
        try{
            int len=Integer.parseUnsignedInt(graphLengthTextField.getText());
            canChart.setMaxLength(len);
        }
        catch (Exception e){
            
        }
    }//GEN-LAST:event_graphLengthTextFieldActionPerformed

    private void removeIDButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_removeIDButtonActionPerformed
        try{
            canChart.removeID(Integer.parseUnsignedInt(idField.getText()),
                              offsetComboBox.getSelectedIndex());
        }
        catch (Exception e){
        }
    }//GEN-LAST:event_removeIDButtonActionPerformed

    private void addIDButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addIDButtonActionPerformed
        try{
            int id = Integer.parseUnsignedInt(idField.getText());
            int offset = offsetComboBox.getSelectedIndex();
            
            canChart.addID(id,
                           offset,
                           typeComboBox.getSelectedIndex(),
                           signedCheckBox.isSelected(),
                           littleEndianCheckBox.isSelected());
        }
        catch (Exception e){
        }
    }//GEN-LAST:event_addIDButtonActionPerformed

    private void littleEndianCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_littleEndianCheckBoxActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_littleEndianCheckBoxActionPerformed
    /**
     * Entry point of USBtinViewer application
     * 
     * @param args The command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(USBtinViewer.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(USBtinViewer.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(USBtinViewer.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(USBtinViewer.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                new USBtinViewer().setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPanel ChartPane;
    private javax.swing.JButton addIDButton;
    private javax.swing.JComboBox bitRate;
    private CANChart canChart;
    private javax.swing.JButton clearButton;
    private javax.swing.JButton connectionButton;
    private javax.swing.JToggleButton followButton;
    private javax.swing.JTextField graphLengthTextField;
    private javax.swing.JTextField idField;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JCheckBox littleEndianCheckBox;
    private javax.swing.JScrollPane logScrollPane;
    private javax.swing.JTable logTable;
    private javax.swing.JTabbedPane mainTabbedPane;
    private javax.swing.JScrollPane monitorScrollPane;
    private javax.swing.JTable monitorTable;
    private javax.swing.JTextField msgData0;
    private javax.swing.JTextField msgData1;
    private javax.swing.JTextField msgData2;
    private javax.swing.JTextField msgData3;
    private javax.swing.JTextField msgData4;
    private javax.swing.JTextField msgData5;
    private javax.swing.JTextField msgData6;
    private javax.swing.JTextField msgData7;
    private javax.swing.JCheckBox msgExt;
    private javax.swing.JTextField msgId;
    private javax.swing.JSpinner msgLength;
    private javax.swing.JCheckBox msgRTR;
	private javax.swing.JComboBox<String> offsetComboBox;
    private javax.swing.JComboBox openmodeComboBox;
	private javax.swing.JButton removeIDButton;
    private javax.swing.JButton sendButton;
    private javax.swing.JTextField sendMessage;
    private javax.swing.JComboBox serialPort;
	private javax.swing.JCheckBox signedCheckBox;
    private javax.swing.JComboBox<String> typeComboBox;
    // End of variables declaration//GEN-END:variables

    /**
     * Receive can message (called via listener)
     * 
     * @param canmsg CAN message
     */
    @Override
    public void receiveCANMessage(CANMessage canmsg) {
        log(new LogMessage(canmsg, null, LogMessage.MessageType.IN, System.currentTimeMillis() - baseTimestamp));
		canChart.addPoint(canmsg);
    }

    /**
     * Insert given message to log list
     * 
     * @param message Message to insert
     */
    public void log(LogMessage message) {
        LogMessageTableModel tm = (LogMessageTableModel) logTable.getModel();
        tm.addMessage(message);
        if (followButton.isSelected()) {
            logTable.scrollRectToVisible(logTable.getCellRect(tm.getRowCount() - 1, 0, true));
        }

        if ((message.type == LogMessage.MessageType.OUT) ||
                (message.type == LogMessage.MessageType.IN)) {
            MonitorMessageTableModel mtm = (MonitorMessageTableModel) monitorTable.getModel();
            mtm.add(message);
        }

    }

    /**
     * Insert message with given typte to log list
     * 
     * @param msg Message to insert
     * @param type Type of message
     */
    public void log(String msg, LogMessage.MessageType type) {
        LogMessageTableModel tm = (LogMessageTableModel) logTable.getModel();
        tm.addMessage(new LogMessage(null, msg, type, System.currentTimeMillis() - baseTimestamp));
        if (followButton.isSelected()) {
            logTable.scrollRectToVisible(logTable.getCellRect(tm.getRowCount() - 1, 0, true));
        }        
    }

    /**
     * Send out CAN message string
     */
    public void send() {
        send(new CANMessage(sendMessage.getText()));
    }

    /**
     * Send out a CAN message
     */
    public void send(CANMessage canmsg) {
        log(new LogMessage(canmsg, null, LogMessage.MessageType.OUT, System.currentTimeMillis() - baseTimestamp));
        try {
            usbtin.send(canmsg);
        } catch (USBtinException e) {
            log(e.getMessage(), LogMessage.MessageType.ERROR);
        }
    }

    /**
     * Convert message input fields to string representation
     */
    protected void msgFields2msgString() {
        if (disableMsgUpdate) {
            return;
        }
        disableMsgUpdate = true;

        int id;
        try {
            id = Integer.parseInt(msgId.getText(), 16);
        } catch (java.lang.NumberFormatException e) {
            id = 0;
        }

        // if id is greater than standard 11bit, set extended flag
        if (id > 0x7ff) {
            msgExt.setSelected(true);
        }

        int length = (Integer) msgLength.getValue();
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            try {
                data[i] = (byte) Integer.parseInt(msgDataFields[i].getText(), 16);
            } catch (java.lang.NumberFormatException e) {
                data[i] = 0;
            }

        }
        CANMessage canmsg = new CANMessage(id, data, msgExt.isSelected(), msgRTR.isSelected());
        sendMessage.setText(canmsg.toString());

        for (int i = 0; i < msgDataFields.length; i++) {
            msgDataFields[i].setEnabled(i < (Integer) msgLength.getValue() && !msgRTR.isSelected());
        }

        disableMsgUpdate = false;
    }

    /**
     * Convert string representation of message to input fields
     */
    protected void msgString2msgFields() {
        if (disableMsgUpdate) {
            return;
        }
        disableMsgUpdate = true;

        CANMessage canmsg = new CANMessage(sendMessage.getText());
        byte[] data = canmsg.getData();

        if (canmsg.isExtended()) {
            msgId.setText(String.format("%08x", canmsg.getId()));
        } else {
            msgId.setText(String.format("%03x", canmsg.getId()));
        }

        msgLength.setValue(data.length);

        msgRTR.setSelected(canmsg.isRtr());
        msgExt.setSelected(canmsg.isExtended());

        for (int i = 0; i < msgDataFields.length; i++) {
            if (i < data.length) {
                msgDataFields[i].setText(String.format("%02x", data[i]));
            }
            msgDataFields[i].setEnabled(i < (Integer) msgLength.getValue() && !msgRTR.isSelected());
        }

        disableMsgUpdate = false;
    }
}

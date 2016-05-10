/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
//package org;

import javax.swing.JPanel;
import javax.swing.BorderFactory;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.*;
import de.fischl.usbtin.CANMessage;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.xy.DefaultXYDataset;
import org.jfree.data.xy.XYDataset;

/**
 *
 * @author Stefano Patassa
 */
public class CANChart extends javax.swing.JPanel {
    
    ChartPanel cp;
    ArrayList<CANData> canData;
    int graphMaxLength = 1000;
    
    public CANChart(){
        canData = new ArrayList<CANData>();
    }
    
    @Override
    public Dimension getPreferredSize() {
        return new Dimension(250,200);
    }
    
    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);       
    }  
    
    public void setMaxLength(int len){
        graphMaxLength=len;
        for(CANData d:canData){
            d.setMaxLength(len);
        }
    }
    
    public void addID(int id, int offset, int type, boolean signed, boolean littleEndian){
        for (CANData d : canData) {
            if (d.ID==id && d.Offset==offset && d.Type == type)
                return;
        }
        canData.add(new CANData(id,
                                graphMaxLength,
                                offset,
                                type,
                                signed,
                                littleEndian));   
    }
    
    public void removeID(int id, int offset){
        for (CANData d : canData) {
            if (d.ID==id && d.Offset==offset){
                canData.remove(d);
                return;
            }
        }
    }
    
    public void addPoint(CANMessage canmsg){
        boolean redraw = false;

        for(CANData d:canData){
            if(canmsg.getId()==d.ID){
                d.addPoint(canmsg.getData());
                
                //TODO ADD DUMMY TO OTHER ARRAYS?
                
                redraw = true;
            }
        }
        
        //Redraw the graph if we have updated the data
        if(redraw){
            DefaultXYDataset ds = new DefaultXYDataset();
            for (CANData d : canData) {
                d.setMaxLength(graphMaxLength);
                double[][] data = { d.getXAxis(), d.toArray() };
                ds.addSeries(d.GetIdString(), data);  
            }
            
            //to avoid filling up all the memory
            if(cp!=null)
                this.remove(cp);  
            
            //Create the chart
            JFreeChart chart = ChartFactory.createXYLineChart("", "t", "value", ds, PlotOrientation.VERTICAL, true, false, false);
            cp = new ChartPanel(chart);
        
            //Add the chart
            this.setLayout(new java.awt.BorderLayout());
            this.add(cp);
            this.validate();
        }
    }
    
    private class CANData{
        
        private ArrayList<Double> Data;
        private ArrayList<Double> Timestamps;
        private int ID;
        private int Offset;
        private int Type;
        private boolean Signed;
        private boolean LittleEndian;
        
        public CANData(int id, int maxLength, int offset, int type, boolean signed, boolean littleEndian){
            ID = id;
            Data = new ArrayList<Double>();
            //Timestamps = new ArrayList<Double>();
            Offset = offset;
            Type = type;
            Signed = signed;
            LittleEndian = littleEndian;
        }
        
        public String GetIdString(){
            String typeString = "Err";
            switch(Type){
                case 0:
                    typeString = "B";
                    break;
                case 1:
                    typeString = "W";
                    break;
                case 2:
                    typeString = "I";
                    break;
                case 3:
                    typeString = "L";
                    break;
            }
            return "ID:" + Integer.toString(ID) + " O:" + Integer.toString(Offset) + " T:" + typeString;
        }
        
        public void setMaxLength(int maxLength){
            while(Data.size()>maxLength){
                Data.remove(0);
            }
        }
        
        public void addPoint(byte[] canData){
            switch(Type){
                //byte
                case 0:
                    if(Offset<canData.length){
                        int num = toByte(canData, Offset, Signed, LittleEndian);// ((int)canData[Offset])&0xFF;
                        Data.add((double)num);
                        //Timestamps.add(Double.longBitsToDouble(System.currentTimeMillis()/1000));
                    }
                    break;
                //word
                case 1:
                    if(Offset<canData.length-1){
                        int num = toWord(canData, Offset, Signed, LittleEndian);
                        Data.add((double)num);
                        //Timestamps.add(Double.longBitsToDouble(System.currentTimeMillis()/1000));
                    }
                    break;
                //integer
                case 2:
                    if(Offset<canData.length-4){
                        int num = toInteger(canData, Offset, Signed, LittleEndian);
                        Data.add((double)num);
                        //Timestamps.add(Double.longBitsToDouble(System.currentTimeMillis()/1000));
                    }
                    break;
                //long
                case 3: 
                    break;
            }
        }
        
        
        public final int toByte(byte[] data, int offset, boolean signed, boolean littleEndian){
            int res = data[offset];
            if(!signed)
                res=res&0xFF;
            return res;
        }
        public final int toWord(byte[] data, int offset, boolean signed, boolean littleEndian){
            int res;
            if(littleEndian)
                res = ((((int)data[offset+1])&0xFF)<<8) + ((int)data[offset+0]&0xFF);
            else 
                res = ((((int)data[offset+0])&0xFF)<<8) + ((int)data[offset+1]&0xFF);
            if(!signed)
                res = res & 0x0000FFFF;
            return res;
        }
        public final int toInteger(byte[] data, int offset, boolean signed, boolean littleEndian){
            if(littleEndian)
                return  ((((int)data[offset+0])<<24) + 
                        (((int)data[offset+1])<<16) + 
                        (((int)data[offset+2])<<8) + 
                        ((int)data[offset+3]));
            else 
                return  ((((int)data[offset+3])&0xFF)<<24) + 
                        ((((int)data[offset+2])&0xFF)<<16) + 
                        ((((int)data[offset+1])&0xFF)<<8) + 
                        ((((int)data[offset+0])&0xFF));
            //if(!signed)
            //    res=res&0xFFFF;
        }
        
        public double[] getXAxis(){
            double[] d = new double[Data.size()];
            for(int i=0; i<Data.size(); i++)
                d[i]=i;
            return d;
            //return Timestamps.stream().mapToDouble(d->d).toArray();
        }
        
        public double[] toArray(){
            return Data.stream().mapToDouble(d->d).toArray();
        }
    }
}

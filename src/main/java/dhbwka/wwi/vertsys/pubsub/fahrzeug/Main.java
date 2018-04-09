/*
 * Copyright © 2018 Dennis Schulmeister-Zimolong
 * 
 * E-Mail: dhbw@windows3.de
 * Webseite: https://www.wpvs.de/
 * 
 * Dieser Quellcode ist lizenziert unter einer
 * Creative Commons Namensnennung 4.0 International Lizenz.
 */
package dhbwka.wwi.vertsys.pubsub.fahrzeug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;


/**
 * Hauptklasse unseres kleinen Progrämmchens.
 *
 * Mit etwas Google-Maps-Erfahrung lassen sich relativ einfach eigene
 * Wegstrecken definieren. Man muss nur Rechtsklick auf einen Punkt machen und
 * "Was ist hier?" anklicken, um die Koordinaten zu sehen. Allerdings speichert
 * Goolge Maps eine Nachkommastelle mehr, als das ITN-Format erlaubt. :-)
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // Fahrzeug-ID abfragen
        String vehicleId = Utils.askInput("Beliebige Fahrzeug-ID", "postauto");

        // Zu fahrende Strecke abfragen
        File workdir = new File("./waypoints");
        String[] waypointFiles = workdir.list((File dir, String name) -> {
            return name.toLowerCase().endsWith(".itn");
        });

        System.out.println();
        System.out.println("Aktuelles Verzeichnis: " + workdir.getCanonicalPath());
        System.out.println();
        System.out.println("Verfügbare Wegstrecken");
        System.out.println();

        for (int i = 0; i < waypointFiles.length; i++) {
            System.out.println("  [" + i + "] " + waypointFiles[i]);
        }

        System.out.println();
        int index = Integer.parseInt(Utils.askInput("Zu fahrende Strecke", "0"));

        // TODO: Methode parseItnFile() unten ausprogrammieren
        List<WGS84> waypoints = parseItnFile(new File(workdir, waypointFiles[index]));

        // Adresse des MQTT-Brokers abfragen
        String mqttAddress = Utils.askInput("MQTT-Broker", Utils.MQTT_BROKER_ADDRESS);

        // TODO: Sicherstellen, dass bei einem Verbindungsabbruch eine sog.
        // LastWill-Nachricht gesendet wird, die auf den Verbindungsabbruch
        // hinweist. Die Nachricht soll eine "StatusMessage" sein, bei der das
        // Feld "type" auf "StatusType.CONNECTION_LOST" gesetzt ist.
        StatusMessage lastsm = new StatusMessage();
        lastsm.vehicleId = vehicleId;
        lastsm.message = "Verbindung abgebrochen";
        lastsm.type = StatusType.CONNECTION_LOST;

        MqttConnectOptions mq = new MqttConnectOptions();
        mq.setWill(Utils.MQTT_TOPIC_NAME, lastsm.toJson(), 0, false);

        // Die Nachricht muss dem MqttConnectOptions-Objekt übergeben werden
        // und soll an das Topic Utils.MQTT_TOPIC_NAME gesendet werden.
        // TODO: Verbindung zum MQTT-Broker herstellen.        
        MqttClient client = new MqttClient(mqttAddress, vehicleId);
        client.connect(mq);
        System.out.println("Client-ID: " + client.getClientId());

        // TODO: Statusmeldung mit "type" = "StatusType.VEHICLE_READY" senden.
        // Die Nachricht soll soll an das Topic Utils.MQTT_TOPIC_NAME gesendet
        // werden.
        StatusMessage sm = new StatusMessage();
        sm.type = StatusType.VEHICLE_READY;
        sm.vehicleId = vehicleId;
        System.out.println("Message: " + sm.message);     
        
        client.publish(Utils.MQTT_TOPIC_NAME, sm.toJson(), 0, false);

        // TODO: Thread starten, der jede Sekunde die aktuellen Sensorwerte
        // des Fahrzeugs ermittelt und verschickt. Die Sensordaten sollen
        // an das Topic Utils.MQTT_TOPIC_NAME + "/" + vehicleId gesendet werden.
        //Hintergrung Thread aufbauen
        Vehicle vehicle = new Vehicle(vehicleId, waypoints);
        vehicle.startVehicle();

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        exec.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                SensorMessage sem = vehicle.getSensorData();
                byte[] json = sem.toJson();
                String msg = new String(json, StandardCharsets.UTF_8);
                String topic = Utils.MQTT_TOPIC_NAME + "/" + vehicleId;
                System.out.println(topic + " -> " + msg);               
                try {
                    MqttMessage message = new MqttMessage(json);
                    client.publish(topic, message);
                } catch (MqttException ex) {
                    Utils.logException(ex);
                }
            }
        }, 0, 1, TimeUnit.SECONDS);

        // Warten, bis das Programm beendet werden soll
        Utils.fromKeyboard.readLine();

        vehicle.stopVehicle();

        // TODO: Oben vorbereitete LastWill-Nachricht hier manuell versenden,
        // da sie bei einem regulären Verbindungsende nicht automatisch
        // verschickt wird.
        if (client.isConnected()) {            
            client.publish(Utils.MQTT_TOPIC_NAME, lastsm.toJson(), 0, false);
            client.disconnect();
            System.out.println("Last-Will-Msg: " + mq.getWillMessage());
        }

        // Anschließend die Verbindung trennen und den oben gestarteten Thread
        // beenden, falls es kein Daemon-Thread ist.        
        exec.shutdown();
    }

    /**
     * Öffnet die in "filename" übergebene ITN-Datei und extrahiert daraus die
     * Koordinaten für die Wegstrecke des Fahrzeugs. Das Dateiformat ist ganz
     * simpel:
     *
     * <pre>
     * 0845453|4902352|Point 1 |0|
     * 0848501|4900249|Point 2 |0|
     * 0849295|4899460|Point 3 |0|
     * 0849796|4897723|Point 4 |0|
     * </pre>
     *
     * Jede Zeile enthält einen Wegpunkt. Die Datenfelder einer Zeile werden
     * durch | getrennt. Das erste Feld ist die "Longitude", das zweite Feld die
     * "Latitude". Die Zahlen müssen durch 100_000.0 geteilt werden.
     *
     * @param file ITN-Datei
     * @return Liste mit Koordinaten
     * @throws java.io.IOException
     */
    public static List<WGS84> parseItnFile(File file) throws IOException {
        List<WGS84> waypoints = new ArrayList<>();
        // TODO: Übergebene Datei parsen und Liste "waypoints" damit füllen     
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String str;
            while ((str = in.readLine()) != null) {                
                String[] ar = str.split("\\|");                
                WGS84 object = new WGS84(Double.parseDouble(ar[0]), Double.parseDouble(ar[1]));
                System.out.println(ar[0] + ", " + ar[1] + ", " + ar[2]);
                waypoints.add(object);
            }
            in.close();
        } catch (IOException e) {
            Utils.logException(e);
        }
        return waypoints;
    }

}

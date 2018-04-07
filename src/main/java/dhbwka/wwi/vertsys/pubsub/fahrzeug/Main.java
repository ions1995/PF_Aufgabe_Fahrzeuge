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
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.lang.Object;
import java.util.Timer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

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
        StatusMessage sm = new StatusMessage();
        //sm.fromJson(json);

        MqttConnectOptions mq = new MqttConnectOptions();
        mq.setWill(Utils.MQTT_TOPIC_NAME, sm.toJson(), 0, true);

        // Die Nachricht muss dem MqttConnectOptions-Objekt übergeben werden
        // und soll an das Topic Utils.MQTT_TOPIC_NAME gesendet werden.
        // TODO: Verbindung zum MQTT-Broker herstellen.
        MemoryPersistence persistance = new MemoryPersistence();
        MqttClient client = new MqttClient(mqttAddress, vehicleId, persistance);//nach dennis ist das richtig

        System.out.println("Client-ID:" + client.toString());
        // TODO: Statusmeldung mit "type" = "StatusType.VEHICLE_READY" senden.
        // Die Nachricht soll soll an das Topic Utils.MQTT_TOPIC_NAME gesendet
        // werden.
        sm.message = StatusType.VEHICLE_READY + "";
        System.out.println("Message: " + sm.message);

        // TODO: Thread starten, der jede Sekunde die aktuellen Sensorwerte
        // des Fahrzeugs ermittelt und verschickt. Die Sensordaten sollen
        // an das Topic Utils.MQTT_TOPIC_NAME + "/" + vehicleId gesendet werden.
        //Hintergrung Thread aufbauen
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();        
        exec.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                String topic = "";
                String msg = "";

                SensorMessage sem = new SensorMessage();
                topic = Utils.MQTT_TOPIC_NAME + "/" + sem.vehicleId;
                String motorAn = "nein";
                if (sem.running) {
                    motorAn = "ja";
                }
                msg = "Sensordaten: "
                        + "Zeitpunkt: " + sem.time
                        + "Motor an? " + motorAn
                        + "\n Killometer/Stunde: " + sem.kmh
                        + "\n Latitude: " + sem.latitude
                        + "\n Longitude: " + sem.longitude
                        + "\n Drehzahl: " + sem.rpm
                        + "\n eingelegter Gang: " + sem.gear;
                // do stuff in this thread

                // hier kann ich mich an dem Sender-Beispiel orientieren
                int qos = 0;

                try {
                    mq.setCleanSession(false);

                    System.out.println("Connect to Broker: " + mqttAddress);

                    client.connect(mq);
                    System.out.println("Connected");
                    System.out.println("Message: \n" + msg);

                    MqttMessage mqttmsg = new MqttMessage(msg.getBytes());
                    mqttmsg.setQos(qos);
                    client.publish(topic, mqttmsg);

                    System.out.println("Message send:");

                    client.disconnect();
                    System.out.println("Disconnected");

                } catch (MqttException e) {
                    e.printStackTrace();
                }
            }
        }, 0, 1, TimeUnit.SECONDS);
        
        
        //Timer timer = new Timer();
        //timer.schedule(thread, 0, 5000);
        //thread.start();
        

        Vehicle vehicle = new Vehicle(vehicleId, waypoints);
        vehicle.startVehicle();

        // Warten, bis das Programm beendet werden soll
        Utils.fromKeyboard.readLine();

        vehicle.stopVehicle();

        // TODO: Oben vorbereitete LastWill-Nachricht hier manuell versenden,
        // da sie bei einem regulären Verbindungsende nicht automatisch
        // verschickt wird.
        if (!client.isConnected()) {
            System.out.println("Last-Will-Msg: " + mq.getWillMessage());
        }

        // Anschließend die Verbindung trennen und den oben gestarteten Thread
        // beenden, falls es kein Daemon-Thread ist.
        //thread.stop();
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
        BufferedReader reader = new BufferedReader(new FileReader(file));
        String line = null;
        StringBuilder stringBuilder = new StringBuilder();
        String ls = System.getProperty("line.separator");

        try {
            System.out.println("List of Waypoints:");
            while ((line = reader.readLine()) != null) {

                String line2 = line;

                String latitude = line2.substring(0, 7);
                String longitude = line2.substring(8, 15);
                // nice to Have: die Bezeichnung der Wegpunkte
                String bezeichnung = line2.substring(16, line.length());
                System.out.println(latitude + "," + longitude + "," + bezeichnung);

                double latitudeZahl = Double.parseDouble(latitude);
                double longitudeZahl = Double.parseDouble(longitude);
                WGS84 object = new WGS84(latitudeZahl, longitudeZahl);
                waypoints.add(object);
            }
            return waypoints;
        } finally {
            reader.close();
        }

    }
}

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.Arrays;

class Task implements Comparable<Task> {
    int taskId;
    int priority;
    int distance;
    int targetBin;

    public Task(int taskId, int priority, int distance, int targetBin) {
        this.taskId = taskId;
        this.priority = priority;
        this.distance = distance;
        this.targetBin = targetBin;
    }

    @Override
    public int compareTo(Task other) {
        if (this.priority != other.priority) {
            return Integer.compare(other.priority, this.priority);
        }
        return Integer.compare(this.distance, other.distance);
    }
}

class InventoryDatabase {
    private final int[] slots;
    private final ReentrantLock[] locks;
    private final SmartWarehouseGUI gui;

    public InventoryDatabase(int size, SmartWarehouseGUI gui) {
        this.slots = new int[size];
        this.locks = new ReentrantLock[size];
        this.gui = gui;
        for (int i = 0; i < size; i++) {
            locks[i] = new ReentrantLock();
            slots[i] = 100; 
        }
    }

    public void updateSlot(int slotId, int quantity, String workerName) {
        locks[slotId].lock();
        try {
            slots[slotId] += quantity;
            gui.logTransaction(workerName + " ACQUIRED lock and updated DB slot " + slotId + ". New Qty: " + slots[slotId]);
            gui.updateInventoryTable(slotId, slots[slotId]);
        } finally {
            locks[slotId].unlock();
        }
    }
}

class Dispatcher {
    private final PriorityBlockingQueue<Task> readyQueue;
    private final SmartWarehouseGUI gui;

    public Dispatcher(SmartWarehouseGUI gui) {
        this.readyQueue = new PriorityBlockingQueue<>();
        this.gui = gui;
    }

    public void addTask(Task task) {
        readyQueue.add(task);
        gui.updateQueueDisplay(readyQueue);
    }

    public Task getNextTask() throws InterruptedException {
        Task task = readyQueue.take();
        gui.updateQueueDisplay(readyQueue);
        return task;
    }

    public void preemptTask(Task task, String workerName) {
        gui.logTransaction("PREEMPTION: Task " + task.taskId + " preempted from " + workerName + " due to fatigue.");
        addTask(task);
    }
}

class Worker extends Thread {
    private int fatigue;
    private final int fatigueThreshold;
    private final Dispatcher dispatcher;
    private final InventoryDatabase inventory;
    private final SmartWarehouseGUI gui;

    public Worker(String name, Dispatcher dispatcher, InventoryDatabase inventory, int threshold, SmartWarehouseGUI gui) {
        super(name);
        this.dispatcher = dispatcher;
        this.inventory = inventory;
        this.fatigueThreshold = threshold;
        this.gui = gui;
        this.fatigue = 0;
    }

    @Override
    public void run() {
        try {
            while (true) {
                gui.updateWorkerStatus(getName(), "Idle", fatigue);
                Task currentTask = dispatcher.getNextTask();
                gui.updateWorkerStatus(getName(), "Executing Task " + currentTask.taskId, fatigue);

                if (fatigue + currentTask.distance > fatigueThreshold) {
                    dispatcher.preemptTask(currentTask, getName());
                    gui.updateWorkerStatus(getName(), "Resting", fatigue);
                    fatigue = 0;
                    Thread.sleep(2000);
                    continue;
                }

                Thread.sleep(500); 
                inventory.updateSlot(currentTask.targetBin, -1, getName());
                fatigue += currentTask.distance;
                gui.updateWorkerStatus(getName(), "Finished Task " + currentTask.taskId, fatigue);
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

class SmartWarehouseGUI extends JFrame {
    private DefaultTableModel workerTableModel;
    private DefaultTableModel inventoryTableModel;
    private JTextArea transactionLog;
    private JTextArea queueDisplay;

    public SmartWarehouseGUI() {
        setTitle("Smart Warehouse Automation Platform - Verification GUI");
        setSize(1000, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new GridLayout(2, 2));

        workerTableModel = new DefaultTableModel(new String[]{"Worker ID", "Status", "Fatigue"}, 0);
        JTable workerTable = new JTable(workerTableModel);
        add(new JScrollPane(workerTable));

        inventoryTableModel = new DefaultTableModel(new String[]{"Bin ID", "Quantity"}, 0);
        JTable inventoryTable = new JTable(inventoryTableModel);
        for (int i = 0; i < 10; i++) {
            inventoryTableModel.addRow(new Object[]{i, 100});
        }
        add(new JScrollPane(inventoryTable));

        queueDisplay = new JTextArea();
        queueDisplay.setEditable(false);
        queueDisplay.setBorder(BorderFactory.createTitledBorder("Scheduler Ready Queue"));
        add(new JScrollPane(queueDisplay));

        transactionLog = new JTextArea();
        transactionLog.setEditable(false);
        transactionLog.setBorder(BorderFactory.createTitledBorder("Database & Synchronization Logs"));
        add(new JScrollPane(transactionLog));
    }

    public void initializeWorkers(int count) {
        for (int i = 1; i <= count; i++) {
            workerTableModel.addRow(new Object[]{"Worker-" + i, "Starting", 0});
        }
    }

    public synchronized void updateWorkerStatus(String workerName, String status, int fatigue) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < workerTableModel.getRowCount(); i++) {
                if (workerTableModel.getValueAt(i, 0).equals(workerName)) {
                    workerTableModel.setValueAt(status, i, 1);
                    workerTableModel.setValueAt(fatigue, i, 2);
                    break;
                }
            }
        });
    }

    public synchronized void updateInventoryTable(int binId, int quantity) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < inventoryTableModel.getRowCount(); i++) {
                if ((int) inventoryTableModel.getValueAt(i, 0) == binId) {
                    inventoryTableModel.setValueAt(quantity, i, 1);
                    break;
                }
            }
        });
    }

    public synchronized void updateQueueDisplay(PriorityBlockingQueue<Task> queue) {
        SwingUtilities.invokeLater(() -> {
            StringBuilder sb = new StringBuilder();
            Task[] tasks = queue.toArray(new Task[0]);
            Arrays.sort(tasks);
            for (Task t : tasks) {
                sb.append("Task ").append(t.taskId)
                  .append(" [Priority: ").append(t.priority)
                  .append(", Dist: ").append(t.distance).append("]\n");
            }
            queueDisplay.setText(sb.toString());
        });
    }

    public synchronized void logTransaction(String message) {
        SwingUtilities.invokeLater(() -> {
            transactionLog.append(message + "\n");
            transactionLog.setCaretPosition(transactionLog.getDocument().getLength());
        });
    }
}

public class SmartWarehousePlatform {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SmartWarehouseGUI gui = new SmartWarehouseGUI();
            gui.setVisible(true);

            InventoryDatabase sharedInventory = new InventoryDatabase(10, gui);
            Dispatcher scheduler = new Dispatcher(gui);
            gui.initializeWorkers(3);

            Worker w1 = new Worker("Worker-1", scheduler, sharedInventory, 15, gui);
            Worker w2 = new Worker("Worker-2", scheduler, sharedInventory, 15, gui);
            Worker w3 = new Worker("Worker-3", scheduler, sharedInventory, 15, gui);

            w1.start();
            w2.start();
            w3.start();

            new Thread(() -> {
                try {
                    int taskId = 1;
                    while (true) {
                        int priority = (int) (Math.random() * 10) + 1;
                        int distance = (int) (Math.random() * 10) + 1;
                        int targetBin = (int) (Math.random() * 10);
                        scheduler.addTask(new Task(taskId++, priority, distance, targetBin));
                        Thread.sleep(1500); 
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
    }
}
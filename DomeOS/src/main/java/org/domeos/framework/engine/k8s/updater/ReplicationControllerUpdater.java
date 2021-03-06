package org.domeos.framework.engine.k8s.updater;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.ReplicationController;
import org.domeos.exception.DeploymentEventException;
import org.domeos.exception.K8sDriverException;
import org.domeos.framework.api.model.deployment.Policy;
import org.domeos.framework.engine.k8s.model.UpdatePhase;
import org.domeos.framework.engine.k8s.model.UpdatePolicy;
import org.domeos.framework.engine.k8s.model.UpdateReplicationCount;
import org.domeos.framework.engine.k8s.model.UpdateStatus;
import org.domeos.framework.engine.k8s.util.KubeUtils;
import org.domeos.framework.engine.k8s.util.PodUtils;
import org.domeos.framework.engine.k8s.util.RCUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Created by anningluo on 2015/12/14.
 */
public class ReplicationControllerUpdater {
    private static ExecutorService executor = Executors.newCachedThreadPool();
    private KubeUtils client = null;
    private ReplicationController oldRC;
    private ReplicationController newRC;
    private UpdateStrategy strategy;
    private final UpdateStatus status = new UpdateStatus(UpdatePhase.Unknown, 0, 0);
    private Future updateFuture = null;
    private StatusChangeHandler<UpdateStatus> statusHandler;  // call every time status change
    private static final ReplicationControllerUpdater EMPTY_UPDATER = new ReplicationControllerUpdater();
    private static Logger logger = LoggerFactory.getLogger(ReplicationControllerUpdater.class);

    public static ReplicationControllerUpdater EmptyUpdater() {
        return EMPTY_UPDATER;
    }

    private static ReplicationControllerUpdater RollingUpdater(
            KubeUtils client,
            ReplicationController oldRC,
            ReplicationController newRC,
            StatusChangeHandler<UpdateStatus> handler,
            Policy policy) {
        ReplicationControllerUpdater updater = new ReplicationControllerUpdater();
        updater.oldRC = oldRC;
        updater.oldRC.getMetadata().setResourceVersion(null);
        updater.newRC = newRC;
        updater.newRC.getMetadata().setResourceVersion(null);
        updater.oldRC.getSpec().setReplicas(0);
        if (policy == null) {
            updater.strategy = new RollingUpdateStrategy();
        } else {
            switch (policy.getPolicyType()) {
                case UserDesign:
                    updater.strategy = new UserDesignUpdateStrategy(policy);
                    break;
                default:
                    updater.strategy = new RollingUpdateStrategy();
            }
        }

        updater.client = client;
        updater.statusHandler = handler;
        updater.updateStatus(oldRC.getSpec().getReplicas(), newRC.getSpec().getReplicas());
        return updater;
    }

    public static ReplicationControllerUpdater RollingUpdater(
            KubeUtils client,
            ReplicationController oldRC,
            ReplicationController newRC) {
        return RollingUpdater(client, oldRC, newRC, null, null);
    }

    public static ReplicationControllerUpdater RollingUpdater(
            KubeUtils client,
            ReplicationController oldRC,
            ReplicationController newRC,
            Policy policy) {
        return RollingUpdater(client, oldRC, newRC, null, policy);
    }

    private ReplicationControllerUpdater(
            KubeUtils client,
            ReplicationController oldRC,
            ReplicationController newRC,
            UpdateStrategy strategy) {
        ReplicationControllerUpdater updater = new ReplicationControllerUpdater();
        updater.oldRC = oldRC;
        updater.oldRC.getMetadata().setResourceVersion(null);
        updater.newRC = newRC;
        updater.newRC.getMetadata().setResourceVersion(null);
        updater.strategy = strategy;
        updater.client = client;
        updater.updateStatus(oldRC.getSpec().getReplicas(), newRC.getSpec().getReplicas());
    }

    private ReplicationControllerUpdater() {
    }

    public void start() {
        startIn(executor);
    }

    private void startIn(ExecutorService otherExecutor) {
        try {
            updateFuture = otherExecutor.submit(new UpdateReplicationController());
        } catch (RejectedExecutionException | NullPointerException e) {
            updateFailed("start executor failed with message=" + e.getMessage());
        }
    }

    public UpdateStatus getStatus() {
        UpdateStatus statusNow;
        synchronized (status) {
            if (updateFuture != null && updateFuture.isDone()
                    && status.getPhase() != UpdatePhase.Failed
                    && status.getPhase() != UpdatePhase.Succeed) {
                status.setPhase(UpdatePhase.Failed);
                status.setReason("unknown reason for update thread terminated");
            }
            statusNow = new UpdateStatus(status);
        }
        return statusNow;
    }

    private void stop() {
        if (updateFuture == null || updateFuture.isDone()) {
            return;
        }
        updateFuture.cancel(false);
        if (!updateFuture.isDone()) {
            updateFuture.cancel(true);
        }
    }

    public void continueUpdate() {

    }

    public void close() {
        if (updateFuture == null || updateFuture.isDone() || updateFuture.isCancelled()) {
            return;
        }
        stop();
    }

    private void handleStatusChange() {
        if (statusHandler != null) {
            statusHandler.handleStatusChange(status);
        }
    }

    // **************************** implement ***************************

    // this function is used for rc update from old version to new version step by step
    public boolean update() throws DeploymentEventException {
        if (oldRC == null || RCUtils.getName(oldRC) == null || RCUtils.getSelector(oldRC) == null
                || newRC == null || RCUtils.getName(newRC) == null || RCUtils.getSelector(newRC) == null) {
            return false;
        }
        // init
        UpdateReplicationCount desireCount = getDesireCount(oldRC, newRC);
        UpdatePolicy todo = null;
        boolean isSuccess = true;
        String reason = null;
        synchronized (status) {
            status.setPhase(UpdatePhase.Running);
            handleStatusChange();
        }

        try {
            // check replication controller
            // make sure old replication controller existed and create new replication controller if not
            // existed
            ReplicationController oldTmpRC = client.replicationControllerInfo(RCUtils.getName(oldRC));
            newRC.getSpec().setReplicas(0);
            ReplicationController newTmpRC = client.replicationControllerInfo(RCUtils.getName(newRC));
            if (oldTmpRC == null) {
                // old deployment not exist
                isSuccess = false;
                reason = "old replication controller " + RCUtils.getName(oldRC) + " is not exist";
            }
            if (newTmpRC == null && isSuccess) {
                newTmpRC = client.createReplicationController(newRC);
                isSuccess = newTmpRC != null;
                if (!isSuccess) {
                    reason = "create new replication controller " + RCUtils.getName(newRC) + " failed";
                }
            }
            if (!isSuccess) {
                updateFailed(reason);
                return false;
            }

            // do update
            do {
                if (todo != null) {
                    // update one
                    isSuccess = updateOneStep(todo, oldRC, newRC);
                    if (!isSuccess) {
                        // update failed
                        // status will change in updateOneStep internal, should here
                        return false;
                    }
                }
                PodList oldPodList = client.listPod(RCUtils.getSelector(oldRC));
                PodList newPodList = client.listPod(RCUtils.getSelector(newRC));
                todo = strategy.scheduleUpdate(desireCount, oldPodList, newPodList);
                if (logger.isDebugEnabled()) {
                    logger.debug("update one step further with policy " + todo);
                }
            } while (todo != null);

            // delete old replication controller
            if (desireCount.getOldReplicaCount() == 0) {
                UpdateReplicationCount readyCountNow = getDesireCount(oldRC, newRC);
                if (readyCountNow.getOldReplicaCount() != 0) {
                    updateFailed("desire old pod count is 0, but get " + readyCountNow.getOldReplicaCount()
                            + ", stop delete and fail update");
                    return false;
                }
                isSuccess = client.deleteReplicationController(RCUtils.getName(oldRC));
                if (!isSuccess) {
                    updateFailed("old replication controller delete failed");
                    return false;
                }
                PodList oldPodList = client.listPod(RCUtils.getSelector(oldRC));
                if (oldPodList != null && oldPodList.getItems() != null) {
                    for (Pod pod : oldPodList.getItems()) {
                        client.deletePod(PodUtils.getName(pod));
                    }
                }
            }
        } catch (IOException | K8sDriverException e) {
            updateFailed("Kubernetes failed with message=" + e.getMessage());
            // isSuccess = false;
            return false;
        }
        synchronized (status) {
            status.setPhase(UpdatePhase.Succeed);
            handleStatusChange();
        }
        return true;
    }

    private boolean updateOneStep(UpdatePolicy policy, ReplicationController oldRC, ReplicationController newRC)
            throws IOException, K8sDriverException, DeploymentEventException {
        if (!checkUpdateStatus(policy, oldRC, newRC)) {
            return false;
        }
        // first step
        if (policy.getFirstActionDelay() > 0) {
            try {
                Thread.sleep(policy.getFirstActionDelay());
            } catch (InterruptedException e) {
                // thread is interrupt, return
                updateFailed("thread is interrupted when before first step in two step update");
                return false;
            }
            if (!checkUpdateStatus(policy, oldRC, newRC)) {
                return false;
            }
        }
        ReplicationController actionTmpRC;
        int replicas;
        if (policy.isRemoveOldFirst()) {
            actionTmpRC = oldRC;
            actionTmpRC.getSpec().setReplicas(policy.getOldReplicaCount());
            replicas = policy.getOldReplicaCount();
        } else {
            actionTmpRC = newRC;
            actionTmpRC.getSpec().setReplicas(policy.getNewReplicaCount());
            replicas = policy.getNewReplicaCount();
        }
        client.scaleReplicationController(RCUtils.getName(actionTmpRC), replicas);
        if (!waitForReady(policy, actionTmpRC, true)) {
            return false;
        }

        // second step
        if (policy.getSecondActionDelay() > 0) {
            try {
                Thread.sleep(policy.getSecondActionDelay());
            } catch (InterruptedException e) {
                // thread is interrupt, continue
                updateFailed("thread is interrupted when before second step in two step update");
                return false;
            }
        }
        if (!checkUpdateStatus(policy, oldRC, newRC)) {
            return false;
        }
        if (!policy.isRemoveOldFirst()) {
            actionTmpRC = oldRC;
            actionTmpRC.getSpec().setReplicas(policy.getOldReplicaCount());
            replicas = policy.getOldReplicaCount();
        } else {
            actionTmpRC = newRC;
            actionTmpRC.getSpec().setReplicas(policy.getNewReplicaCount());
            replicas = policy.getNewReplicaCount();
        }
        client.scaleReplicationController(RCUtils.getName(actionTmpRC), replicas);

        return waitForReady(policy, actionTmpRC, false) && checkUpdateStatus(policy, oldRC, newRC);
    }

    private boolean waitForReady(UpdatePolicy policy, ReplicationController rc, boolean isFirstAction)
            throws IOException, K8sDriverException, DeploymentEventException {
        int desireInt;
        if (isFirstAction ^ policy.isRemoveOldFirst()) {
            // for old
            desireInt = policy.getNewReplicaCount();
        } else {
            desireInt = policy.getOldReplicaCount();
        }
        long start = System.currentTimeMillis();
        PodList tmpPodList = client.listPod(RCUtils.getSelector(rc));
        if (tmpPodList == null && desireInt != 0) {
            return false;
        }
        while (tmpPodList != null && (desireInt != PodUtils.getPodReadyNumber(tmpPodList.getItems())
                || desireInt != tmpPodList.getItems().size())) {
            client.clearNotRunningPod(tmpPodList);
            /*
            try {
                if (failedJudgment.isAnyFailed(tmpPodList)) {
                    return false;
                }
            } catch (ParseException e) {
                String message = "parse pod start time failed";
                logger.error(message);
                updateFailed(message);
                return false;
            }
            */
            long waitTime;
            if (policy.getMaxTimeForReady() < 0) {
                waitTime = policy.getCheckReadyPeriod();
            } else {
                waitTime = Math.min(policy.getCheckReadyPeriod(),
                        policy.getMaxTimeForReady() - System.currentTimeMillis() + start);
            }
            if (waitTime < 0) {
                updateFailed("wait for once update ready timeout");
                return false;
            }
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                updateFailed("thread is interrupted in waiting for once update ready");
                return false;
            }
            tmpPodList = client.listPod(RCUtils.getSelector(rc));
            if (tmpPodList == null && desireInt != 0) {
                return false;
            }
        }
        return true;
    }

    private UpdateReplicationCount getPodReadyCount(ReplicationController oldRC, ReplicationController newRC)
            throws IOException, K8sDriverException {
        PodList oldPods = client.listPod(RCUtils.getSelector(oldRC));
        PodList newPods = client.listPod(RCUtils.getSelector(newRC));
        return new UpdateReplicationCount(PodUtils.getPodReadyNumber(oldPods.getItems()),
                PodUtils.getPodReadyNumber(newPods.getItems()));
    }

    /*
    public boolean checkUpdateResultStatus(UpdatePolicy policy, ReplicationController oldRC, ReplicationController newRC)
            throws KubeResponseException, IOException, K8sDriverException, KubeInternalErrorException {
        UpdateReplicationCount readyCountNow = getUpdateStatus(oldRC, newRC);

    }
    */

    // this function is used to check condition in policy whether be satisfied
    private boolean checkUpdateStatus(UpdatePolicy policy, ReplicationController oldRC, ReplicationController newRC)
            throws IOException, K8sDriverException {
        UpdateReplicationCount readyCount = getPodReadyCount(oldRC, newRC);
        int totalReadyCountNow = readyCount.getNewReplicaCount() + readyCount.getOldReplicaCount();
        if ((policy.getMinPodReadyCount() > 0 && totalReadyCountNow < policy.getMinPodReadyCount())
                || (policy.getMaxPodReadyCount() > 0 && totalReadyCountNow > policy.getMaxPodReadyCount())) {
            updateFailed("check update status failed with oldPodReadyCount=" + readyCount.getOldReplicaCount()
                    + ", newPodReadyCount=" + readyCount.getNewReplicaCount() + ", but require minPodReadyCount="
                    + policy.getMinPodReadyCount() + ", maxPodReadCount=" + policy.getMaxPodReadyCount()
            );
            return false;
        }
        return true;
    }

    /*
        public static boolean checkUpdateStatus(UpdatePolicy policy, UpdateReplicationCount readyCountNow) {
            int totalReadyCountNow = readyCountNow.getNewReplicaCount() + readyCountNow.getOldReplicaCount();
            if ((policy.getMinPodReadyCount() > 0 && totalReadyCountNow < policy.getMinPodReadyCount())
                    || (policy.getMaxPodReadyCount() > 0 && totalReadyCountNow > policy.getMaxPodReadyCount())) {
                return false;
            }
            return true;
        }
    */
    private UpdateReplicationCount getDesireCount(ReplicationController oldRC, ReplicationController newRC) {
        return new UpdateReplicationCount(oldRC.getSpec().getReplicas(), newRC.getSpec().getReplicas());
    }

    private class UpdateReplicationController implements Runnable {
        @Override
        public void run() {
            try {
                update();
            } catch (DeploymentEventException e) {
                // do nothing
            }
        }
    }

    private void updateFailed(String reason) {
        logger.error("update failed for reason=" + reason);
        synchronized (status) {
            status.setPhase(UpdatePhase.Failed);
            status.setReason(reason);
            handleStatusChange();
        }
    }

    private void updateStatus(int oldReplicas, int newReplicas) {
        synchronized (status) {
            status.setPhase(UpdatePhase.Starting);
            status.setOldReplicaCount(oldReplicas);
            status.setNewReplicaCount(newReplicas);
            handleStatusChange();
        }
    }
}

/*
class UpdateStrategyFactory {
    private Logger logger = LoggerFactory.getLogger(UpdateStrategyFactory.class);
    UpdateStrategy getUpdateStrategy(String strategyName) {
        try {
            Class classType = Class.forName(strategyName);
            return (UpdateStrategy)classType.newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            logger.fatal("create update strategy=" + strategyName + " failed");
            return null;
        }
    }
}
*/


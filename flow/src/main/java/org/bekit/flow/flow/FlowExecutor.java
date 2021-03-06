/* 
 * 作者：钟勋 (e-mail:zhongxunking@163.com)
 */

/*
 * 修订记录:
 * @author 钟勋 2016-12-18 23:48 创建
 */
package org.bekit.flow.flow;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bekit.common.method.MethodExecutor;
import org.bekit.common.transaction.TxExecutor;
import org.bekit.event.EventPublisher;
import org.bekit.flow.annotation.locker.FlowLock;
import org.bekit.flow.annotation.locker.FlowUnlock;
import org.bekit.flow.annotation.locker.StateLock;
import org.bekit.flow.annotation.locker.StateUnlock;
import org.bekit.flow.engine.FlowContext;
import org.bekit.flow.event.*;
import org.bekit.flow.locker.TheFlowLockerExecutor;
import org.bekit.flow.mapper.TheFlowMapperExecutor;
import org.bekit.flow.processor.ProcessorExecutor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * 流程执行器
 */
@AllArgsConstructor
public class FlowExecutor {
    // 流程名称
    @Getter
    private final String flowName;
    // 流程
    @Getter
    private final Object flow;
    // 开始节点
    private final String startNode;
    // 结束节点
    private final Set<String> endNodes;
    // 节点执行器Map（key：节点名称）
    private final Map<String, NodeExecutor> nodeExecutorMap;
    // 映射器执行器
    private final TheFlowMapperExecutor mapperExecutor;
    // 加锁器执行器
    private final TheFlowLockerExecutor lockerExecutor;
    // 事务执行器
    private final TxExecutor txExecutor;
    // 事件发布器
    private final EventPublisher eventPublisher;

    /**
     * 执行
     *
     * @param context 流程上下文
     * @throws Throwable 执行过程中发生任何异常都会往外抛
     */
    public void execute(FlowContext<?> context) throws Throwable {
        try {
            // 发布流程开始事件
            eventPublisher.publish(new FlowStartEvent(flowName, context));
            // 映射出节点
            String node = mappingNode(context, startNode);
            try {
                // 流程前置处理
                node = beforeFlow(context, node);
                try {
                    // 状态前置处理
                    node = beforeState(context, node);
                    if (!endNodes.contains(node)) {
                        // 获取节点执行器
                        NodeExecutor nodeExecutor = getRequiredNodeExecutor(node);
                        do {
                            // 发布正在执行的节点事件
                            eventPublisher.publish(new ExecutingNodeEvent(flowName, node, context));
                            // 执行节点
                            node = nodeExecutor.execute(flow, context);
                            // 是否中断流程
                            if (node == null) {
                                break;
                            }
                            // 获取下个节点执行器
                            nodeExecutor = getRequiredNodeExecutor(node);
                            // 发布节点选择事件
                            eventPublisher.publish(new DecidedNodeEvent(flowName, node, context));
                            // 下个节点是否是状态节点
                            if (nodeExecutor.isHaveState()) {
                                // 发布状态节点选择事件
                                eventPublisher.publish(new DecidedStateNodeEvent(flowName, node, context));
                                // 下个节点是否自动执行
                                if (nodeExecutor.isAutoExecute()) {
                                    afterState(context);
                                    // 刷新下个节点（防止状态锁解锁后目标对象被其他线程抢占并执行到其他节点，此处更新到最新节点）
                                    node = beforeState(context, node);
                                    nodeExecutor = getRequiredNodeExecutor(node);
                                }
                            }
                        } while (nodeExecutor.isAutoExecute());
                    }
                    // 状态后置处理
                    afterState(context);
                } catch (Throwable e) {
                    // 状态异常处理
                    afterStateException(context);
                    throw e;
                }
            } finally {
                // 流程后置处理
                afterFlow(context);
            }
        } catch (Throwable e) {
            // 发布流程异常事件
            eventPublisher.publish(new FlowExceptionEvent(flowName, e, context));
            throw e;
        } finally {
            // 发布流程结束事件
            eventPublisher.publish(new FlowEndEvent(flowName, context));
        }
    }

    // 映射出节点
    private String mappingNode(FlowContext<?> context, String defaultNode) throws Throwable {
        String node = defaultNode;
        if (mapperExecutor != null) {
            node = mapperExecutor.execute(context);
        }
        return node;
    }

    // 流程前置处理
    private String beforeFlow(FlowContext<?> context, String defaultNode) throws Throwable {
        String node = defaultNode;
        if (lockerExecutor != null && lockerExecutor.contain(FlowLock.class)) {
            Object newTarget = lockerExecutor.execute(FlowLock.class, context);
            ((FlowContext<Object>) context).refreshTarget(newTarget);
            node = mappingNode(context, node);
        }
        return node;
    }

    // 状态前置处理
    private String beforeState(FlowContext<?> context, String defaultNode) throws Throwable {
        String node = defaultNode;
        txExecutor.createTx();
        if (lockerExecutor != null && lockerExecutor.contain(StateLock.class)) {
            Object newTarget = lockerExecutor.execute(StateLock.class, context);
            ((FlowContext<Object>) context).refreshTarget(newTarget);
            node = mappingNode(context, node);
        }
        return node;
    }

    // 获取节点执行器
    private NodeExecutor getRequiredNodeExecutor(String node) {
        NodeExecutor nodeExecutor = nodeExecutorMap.get(node);
        if (nodeExecutor == null) {
            throw new IllegalStateException(String.format("流程[%s]不存在节点[%s]", flowName, node));
        }
        return nodeExecutor;
    }

    // 状态后置处理
    private void afterState(FlowContext<?> context) throws Throwable {
        if (lockerExecutor != null && lockerExecutor.contain(StateUnlock.class)) {
            lockerExecutor.execute(StateUnlock.class, context);
        }
        txExecutor.commitTx();
    }

    // 状态异常处理
    private void afterStateException(FlowContext<?> context) throws Throwable {
        try {
            if (lockerExecutor != null && lockerExecutor.contain(StateUnlock.class)) {
                lockerExecutor.execute(StateUnlock.class, context);
            }
        } finally {
            txExecutor.rollbackTx();
        }
    }

    // 流程后置处理
    private void afterFlow(FlowContext<?> context) throws Throwable {
        if (lockerExecutor != null && lockerExecutor.contain(FlowUnlock.class)) {
            lockerExecutor.execute(FlowUnlock.class, context);
        }
    }

    /**
     * 节点执行器
     */
    @AllArgsConstructor
    public static class NodeExecutor {
        // 节点名称
        @Getter
        private final String nodeName;
        // 是否有状态
        @Getter
        private final boolean haveState;
        // 是否自动执行
        @Getter
        private final boolean autoExecute;
        // 处理器执行器
        private final ProcessorExecutor processorExecutor;
        // 节点决策器执行器
        private final NodeDeciderExecutor nodeDeciderExecutor;

        /**
         * 执行
         *
         * @param flow    流程
         * @param context 流程上下文
         * @return 下个节点
         * @throws Throwable 执行过程中发生任何异常都会往外抛
         */
        public String execute(Object flow, FlowContext<?> context) throws Throwable {
            Object processResult = null;
            if (processorExecutor != null) {
                // 执行节点处理器
                processResult = processorExecutor.execute(context);
            }
            // 执行节点决策器
            return nodeDeciderExecutor.execute(flow, processResult, context);
        }

        /**
         * 节点决策器执行器
         */
        public static class NodeDeciderExecutor extends MethodExecutor {
            // 参数类型
            private final ParameterType parameterType;

            public NodeDeciderExecutor(ParameterType parameterType, Method nodeDeciderMethod) {
                super(nodeDeciderMethod);
                this.parameterType = parameterType;
            }

            /**
             * 执行
             *
             * @param flow          流程
             * @param processResult 处理器执行结果
             * @param context       流程上下文
             * @return 下个节点名称
             * @throws Throwable 执行过程中发生任何异常都会往外抛
             */
            public String execute(Object flow, Object processResult, FlowContext<?> context) throws Throwable {
                switch (parameterType) {
                    case NONE:
                        return (String) execute(flow, new Object[]{});
                    case ONLY_PROCESS_RESULT:
                        return (String) execute(flow, new Object[]{processResult});
                    case ONLY_FLOW_CONTEXT:
                        return (String) execute(flow, new Object[]{context});
                    case PROCESS_RESULT_AND_FLOW_CONTEXT:
                        return (String) execute(flow, new Object[]{processResult, context});
                    default:
                        throw new IllegalStateException("节点决策器执行器内部要素不对");
                }
            }

            /**
             * 节点决策器方法参数类型
             */
            public enum ParameterType {
                /**
                 * 无参数
                 */
                NONE,
                /**
                 * 只有处理结果参数
                 */
                ONLY_PROCESS_RESULT,
                /**
                 * 只有流程上下文
                 */
                ONLY_FLOW_CONTEXT,
                /**
                 * 处理结果和流程上下文都有
                 */
                PROCESS_RESULT_AND_FLOW_CONTEXT,;
            }
        }
    }
}

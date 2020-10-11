/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.groovy.linq.dsl;

import org.apache.groovy.linq.dsl.expression.AbstractGinqExpression;
import org.apache.groovy.linq.dsl.expression.DataSourceExpression;
import org.apache.groovy.linq.dsl.expression.FilterExpression;
import org.apache.groovy.linq.dsl.expression.FromExpression;
import org.apache.groovy.linq.dsl.expression.GinqExpression;
import org.apache.groovy.linq.dsl.expression.GroupExpression;
import org.apache.groovy.linq.dsl.expression.JoinExpression;
import org.apache.groovy.linq.dsl.expression.LimitExpression;
import org.apache.groovy.linq.dsl.expression.OnExpression;
import org.apache.groovy.linq.dsl.expression.OrderExpression;
import org.apache.groovy.linq.dsl.expression.SelectExpression;
import org.apache.groovy.linq.dsl.expression.WhereExpression;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Build the AST for GINQ
 *
 * @since 4.0.0
 */
public class GinqAstBuilder extends CodeVisitorSupport implements SyntaxErrorReportable {
    private final Deque<GinqExpression> ginqExpressionStack = new ArrayDeque<>();
    private GinqExpression latestGinqExpression;
    private final SourceUnit sourceUnit;

    public GinqAstBuilder(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    public GinqExpression getGinqExpression() {
        return latestGinqExpression;
    }

    private void setLatestGinqExpressionClause(AbstractGinqExpression ginqExpressionClause) {
        GinqExpression ginqExpression = ginqExpressionStack.peek();
        ginqExpression.putNodeMetaData(__LATEST_GINQ_EXPRESSION_CLAUSE, ginqExpressionClause);
    }

    private AbstractGinqExpression getLatestGinqExpressionClause() {
        GinqExpression ginqExpression = ginqExpressionStack.peek();
        return ginqExpression.getNodeMetaData(__LATEST_GINQ_EXPRESSION_CLAUSE);
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        super.visitMethodCallExpression(call);
        final String methodName = call.getMethodAsString();

        if ("from".equals(methodName)) {
            ginqExpressionStack.push(new GinqExpression()); // store the result
        }

        GinqExpression currentGinqExpression = ginqExpressionStack.peek();
        AbstractGinqExpression latestGinqExpressionClause = getLatestGinqExpressionClause();

        if ("from".equals(methodName)  || JoinExpression.isJoinExpression(methodName)) {
            ArgumentListExpression arguments = (ArgumentListExpression) call.getArguments();
            if (arguments.getExpressions().size() != 1) {
                this.collectSyntaxError(
                        new GinqSyntaxError(
                                "Only 1 argument expected for `" + methodName + "`, e.g. `" + methodName + " n in nums`",
                                call.getLineNumber(), call.getColumnNumber()
                        )
                );
            }
            final Expression expression = arguments.getExpression(0);
            if (!(expression instanceof BinaryExpression
                    && ((BinaryExpression) expression).getOperation().getType() == Types.KEYWORD_IN)) {
                this.collectSyntaxError(
                        new GinqSyntaxError(
                                "`in` is expected for `" + methodName + "`, e.g. `" + methodName + " n in nums`",
                                call.getLineNumber(), call.getColumnNumber()
                        )
                );
            }
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            Expression aliasExpr = binaryExpression.getLeftExpression();
            Expression dataSourceExpr;
            if (null == latestGinqExpression) {
                dataSourceExpr = binaryExpression.getRightExpression();
            } else {
                // use the nested linq expresion and clear it
                dataSourceExpr = latestGinqExpression;
                latestGinqExpression = null;
            }

            DataSourceExpression dataSourceExpression;
            if ("from".equals(methodName)) {
                dataSourceExpression = new FromExpression(aliasExpr, dataSourceExpr);
                currentGinqExpression.setFromExpression((FromExpression) dataSourceExpression);
            } else {
                dataSourceExpression = new JoinExpression(methodName, aliasExpr, dataSourceExpr);
                currentGinqExpression.addJoinExpression((JoinExpression) dataSourceExpression);
            }
            dataSourceExpression.setSourcePosition(call);
            setLatestGinqExpressionClause(dataSourceExpression);

            return;
        }

        if ("where".equals(methodName) || "on".equals(methodName)) {
            Expression filterExpr = ((ArgumentListExpression) call.getArguments()).getExpression(0);

            if (filterExpr instanceof BinaryExpression && ((BinaryExpression) filterExpr).getOperation().getType() == Types.KEYWORD_IN) {
                if (null != latestGinqExpression) {
                    // use the nested ginq and clear it
                    ((BinaryExpression) filterExpr).setRightExpression(latestGinqExpression);
                    latestGinqExpression = null;
                }
            }

            FilterExpression filterExpression;
            if ("where".equals(methodName)) {
                filterExpression = new WhereExpression(filterExpr);
            } else {
                filterExpression = new OnExpression(filterExpr);
            }

            filterExpression.setSourcePosition(call);

            if (latestGinqExpressionClause instanceof JoinExpression && filterExpression instanceof OnExpression) {
                ((JoinExpression) latestGinqExpressionClause).setOnExpression((OnExpression) filterExpression);
            } else if (latestGinqExpressionClause instanceof DataSourceExpression && filterExpression instanceof WhereExpression) {
                final DataSourceExpression dataSourceExpression = (DataSourceExpression) latestGinqExpressionClause;

                if (null != dataSourceExpression.getGroupExpression() || null != dataSourceExpression.getOrderExpression() || null != dataSourceExpression.getLimitExpression()) {
                    this.collectSyntaxError(new GinqSyntaxError(
                            "The preceding clause of `" + methodName + "` should be `from`/" + "join clause",
                            call.getLineNumber(), call.getColumnNumber()
                    ));
                }
                dataSourceExpression.setWhereExpression((WhereExpression) filterExpression);
            } else  {
                this.collectSyntaxError(new GinqSyntaxError(
                        "The preceding clause of `" + methodName + "` should be " + ("on".equals(methodName) ? "" : "`from`/") + "join clause",
                        call.getLineNumber(), call.getColumnNumber()
                ));
            }

            return;
        }

        if ("groupby".equals(methodName)) {
            GroupExpression groupExpression = new GroupExpression(call.getArguments());
            groupExpression.setSourcePosition(call);

            if (latestGinqExpressionClause instanceof DataSourceExpression) {
                ((DataSourceExpression) latestGinqExpressionClause).setGroupExpression(groupExpression);
            } else {
                throw new GroovyBugError("The preceding expression is not a DataSourceExpression: " + latestGinqExpressionClause);
            }

            return;
        }

        if ("orderby".equals(methodName)) {
            OrderExpression orderExpression = new OrderExpression(call.getArguments());
            orderExpression.setSourcePosition(call);

            if (latestGinqExpressionClause instanceof DataSourceExpression) {
                ((DataSourceExpression) latestGinqExpressionClause).setOrderExpression(orderExpression);
            } else {
                throw new GroovyBugError("The preceding expression is not a DataSourceExpression: " + latestGinqExpressionClause);
            }

            return;
        }

        if ("limit".equals(methodName)) {
            LimitExpression limitExpression = new LimitExpression(call.getArguments());
            limitExpression.setSourcePosition(call);

            if (latestGinqExpressionClause instanceof DataSourceExpression) {
                ((DataSourceExpression) latestGinqExpressionClause).setLimitExpression(limitExpression);
            } else {
                throw new GroovyBugError("The preceding expression is not a DataSourceExpression: " + latestGinqExpressionClause);
            }

            return;
        }

        if ("select".equals(methodName)) {
            SelectExpression selectExpression = new SelectExpression(call.getArguments());
            selectExpression.setSourcePosition(call);

            currentGinqExpression.setSelectExpression(selectExpression);
            setLatestGinqExpressionClause(selectExpression);

            latestGinqExpression = ginqExpressionStack.pop();

            return;
        }
    }

    @Override
    public SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    private static final String __LATEST_GINQ_EXPRESSION_CLAUSE = "__latestGinqExpressionClause";
}

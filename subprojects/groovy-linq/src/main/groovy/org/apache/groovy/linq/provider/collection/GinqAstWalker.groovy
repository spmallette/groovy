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
package org.apache.groovy.linq.provider.collection

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.apache.groovy.linq.dsl.GinqSyntaxError
import org.apache.groovy.linq.dsl.GinqVisitor
import org.apache.groovy.linq.dsl.SyntaxErrorReportable
import org.apache.groovy.linq.dsl.expression.AbstractGinqExpression
import org.apache.groovy.linq.dsl.expression.DataSourceExpression
import org.apache.groovy.linq.dsl.expression.FromExpression
import org.apache.groovy.linq.dsl.expression.GinqExpression
import org.apache.groovy.linq.dsl.expression.GroupExpression
import org.apache.groovy.linq.dsl.expression.JoinExpression
import org.apache.groovy.linq.dsl.expression.LimitExpression
import org.apache.groovy.linq.dsl.expression.OnExpression
import org.apache.groovy.linq.dsl.expression.OrderExpression
import org.apache.groovy.linq.dsl.expression.SelectExpression
import org.apache.groovy.linq.dsl.expression.WhereExpression
import org.codehaus.groovy.GroovyBugError
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ExpressionTransformer
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.LambdaExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Types

import java.util.stream.Collectors

import static org.codehaus.groovy.ast.tools.GeneralUtils.args
import static org.codehaus.groovy.ast.tools.GeneralUtils.callX
import static org.codehaus.groovy.ast.tools.GeneralUtils.ctorX
import static org.codehaus.groovy.ast.tools.GeneralUtils.lambdaX
import static org.codehaus.groovy.ast.tools.GeneralUtils.param
import static org.codehaus.groovy.ast.tools.GeneralUtils.params
import static org.codehaus.groovy.ast.tools.GeneralUtils.propX
import static org.codehaus.groovy.ast.tools.GeneralUtils.stmt

/**
 * Visit AST of GINQ and generate target method calls for GINQ
 *
 * @since 4.0.0
 */
@CompileStatic
class GinqAstWalker implements GinqVisitor<Object>, SyntaxErrorReportable {
    private final SourceUnit sourceUnit
    private GinqExpression currentGinqExpression

    GinqAstWalker(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit
    }

    @Override
    MethodCallExpression visitGinqExpression(GinqExpression ginqExpression) {
        this.currentGinqExpression = ginqExpression
        FromExpression fromExpression = ginqExpression.getFromExpression()
        MethodCallExpression fromMethodCallExpression = this.visitFromExpression(fromExpression)

        MethodCallExpression selectMethodReceiver = fromMethodCallExpression

        JoinExpression lastJoinExpression = null
        MethodCallExpression lastJoinMethodCallExpression = null
        for (JoinExpression joinExpression : ginqExpression.getJoinExpressionList()) {
            joinExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, lastJoinMethodCallExpression ?: fromMethodCallExpression)
            joinExpression.putNodeMetaData(__DATA_SOURCE_EXPRESSION, lastJoinExpression ?: fromExpression)

            lastJoinExpression = joinExpression
            lastJoinMethodCallExpression = this.visitJoinExpression(lastJoinExpression)
        }

        if (lastJoinMethodCallExpression) {
            selectMethodReceiver = lastJoinMethodCallExpression
        }

        SelectExpression selectExpression = ginqExpression.getSelectExpression()
        selectExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, selectMethodReceiver)
        selectExpression.putNodeMetaData(__DATA_SOURCE_EXPRESSION, lastJoinExpression ?: fromExpression)

        MethodCallExpression selectMethodCallExpression = this.visitSelectExpression(selectExpression)

        return selectMethodCallExpression
    }

    @Override
    MethodCallExpression visitFromExpression(FromExpression fromExpression) {
        MethodCallExpression resultMethodCallExpression
        MethodCallExpression fromMethodCallExpression = constructFromMethodCallExpression(fromExpression.dataSourceExpr)
        resultMethodCallExpression = fromMethodCallExpression

        WhereExpression whereExpression = fromExpression.whereExpression

        return decorateDataSourceMethodCallExpression(resultMethodCallExpression, fromExpression, whereExpression)
    }

    private MethodCallExpression decorateDataSourceMethodCallExpression(MethodCallExpression dataSourceMethodCallExpression,
                                                                        DataSourceExpression dataSourceExpression, WhereExpression whereExpression) {
        if (whereExpression) {
            whereExpression.putNodeMetaData(__DATA_SOURCE_EXPRESSION, dataSourceExpression)
            whereExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, dataSourceMethodCallExpression)

            MethodCallExpression whereMethodCallExpression = visitWhereExpression(whereExpression)
            dataSourceMethodCallExpression = whereMethodCallExpression
        }

        GroupExpression groupExpression = dataSourceExpression.groupExpression
        if (groupExpression) {
            groupExpression.putNodeMetaData(__DATA_SOURCE_EXPRESSION, dataSourceExpression)
            groupExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, dataSourceMethodCallExpression)

            MethodCallExpression groupMethodCallExpression = visitGroupExpression(groupExpression)
            dataSourceMethodCallExpression = groupMethodCallExpression
        }

        OrderExpression orderExpression = dataSourceExpression.orderExpression
        if (orderExpression) {
            orderExpression.putNodeMetaData(__DATA_SOURCE_EXPRESSION, dataSourceExpression)
            orderExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, dataSourceMethodCallExpression)

            MethodCallExpression orderMethodCallExpression = visitOrderExpression(orderExpression)
            dataSourceMethodCallExpression = orderMethodCallExpression
        }

        LimitExpression limitExpression = dataSourceExpression.limitExpression
        if (limitExpression) {
            limitExpression.putNodeMetaData(__DATA_SOURCE_EXPRESSION, dataSourceExpression)
            limitExpression.putNodeMetaData(__METHOD_CALL_RECEIVER, dataSourceMethodCallExpression)

            MethodCallExpression limitMethodCallExpression = visitLimitExpression(limitExpression)
            dataSourceMethodCallExpression = limitMethodCallExpression
        }

        return dataSourceMethodCallExpression
    }

    @Override
    MethodCallExpression visitJoinExpression(JoinExpression joinExpression) {
        Expression receiver = joinExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        OnExpression onExpression = joinExpression.onExpression

        if (!onExpression && !joinExpression.crossJoin) {
            this.collectSyntaxError(
                    new GinqSyntaxError(
                            "`on` clause is expected for `" + joinExpression.joinName + "`",
                            joinExpression.getLineNumber(), joinExpression.getColumnNumber()
                    )
            )
        }

        WhereExpression whereExpression = joinExpression.whereExpression

        MethodCallExpression joinMethodCallExpression = constructJoinMethodCallExpression(receiver, joinExpression, onExpression, whereExpression)

        return joinMethodCallExpression
    }

    @Override
    MethodCallExpression visitOnExpression(OnExpression onExpression) {
        return null // do nothing
    }

    @CompileDynamic
    private MethodCallExpression constructFromMethodCallExpression(Expression dataSourceExpr) {
        MethodCallExpression fromMethodCallExpression = macro {
            $v{ makeQueryableCollectionClassExpression() }.from($v {
                if (dataSourceExpr instanceof AbstractGinqExpression) {
                    return this.visit((AbstractGinqExpression) dataSourceExpr)
                } else {
                    return dataSourceExpr
                }
            })
        }

        return fromMethodCallExpression
    }

    private MethodCallExpression constructJoinMethodCallExpression(
            Expression receiver, JoinExpression joinExpression,
            OnExpression onExpression, WhereExpression whereExpression) {

        DataSourceExpression otherDataSourceExpression = joinExpression.getNodeMetaData(__DATA_SOURCE_EXPRESSION)
        Expression otherAliasExpr = otherDataSourceExpression.aliasExpr

        String otherParamName = otherAliasExpr.text
        Expression filterExpr = EmptyExpression.INSTANCE
        if (onExpression) {
            filterExpr = onExpression.getFilterExpr()
            Tuple2<String, Expression> paramNameAndLambdaCode = correctVariablesOfLambdaExpression(otherDataSourceExpression, filterExpr)
            otherParamName = paramNameAndLambdaCode.v1
            filterExpr = paramNameAndLambdaCode.v2
        }

        MethodCallExpression resultMethodCallExpression
        MethodCallExpression joinMethodCallExpression = callX(receiver, joinExpression.joinName.replace('join', 'Join'),
                args(
                        constructFromMethodCallExpression(joinExpression.dataSourceExpr),
                        null == onExpression ? EmptyExpression.INSTANCE : lambdaX(
                                params(
                                        param(ClassHelper.DYNAMIC_TYPE, otherParamName),
                                        param(ClassHelper.DYNAMIC_TYPE, joinExpression.aliasExpr.text)
                                ),
                                stmt(filterExpr)
                        )
                )
        )
        resultMethodCallExpression = joinMethodCallExpression

        if (joinExpression.crossJoin) {
            // cross join does not need `on` clause
            Expression lastArgumentExpression = ((ArgumentListExpression) joinMethodCallExpression.arguments).getExpressions().removeLast()
            if (EmptyExpression.INSTANCE !== lastArgumentExpression) {
                throw new GroovyBugError("Wrong argument removed")
            }
        }

        return decorateDataSourceMethodCallExpression(resultMethodCallExpression, joinExpression, whereExpression)
    }

    @Override
    MethodCallExpression visitWhereExpression(WhereExpression whereExpression) {
        DataSourceExpression dataSourceExpression = whereExpression.getNodeMetaData(__DATA_SOURCE_EXPRESSION)
        Expression fromMethodCallExpression = whereExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        Expression filterExpr = whereExpression.getFilterExpr()

        if (filterExpr instanceof BinaryExpression && ((BinaryExpression) filterExpr).operation.type == Types.KEYWORD_IN) {
            filterExpr = filterExpr.transformExpression(new ExpressionTransformer() {
                @Override
                Expression transform(Expression expression) {
                    if (expression instanceof AbstractGinqExpression) {
                        return callX((Expression) GinqAstWalker.this.visit((AbstractGinqExpression) expression), "toList")
                    }

                    return expression.transformExpression(this)
                }
            })
        }

        return callXWithLambda(fromMethodCallExpression, "where", dataSourceExpression, filterExpr)
    }

    @Override
    MethodCallExpression visitGroupExpression(GroupExpression groupExpression) {
        DataSourceExpression dataSourceExpression = groupExpression.getNodeMetaData(__DATA_SOURCE_EXPRESSION)
        Expression groupMethodCallReceiver = groupExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        Expression classifierExpr = groupExpression.classifierExpr

        List<Expression> argumentExpressionList = ((ArgumentListExpression) classifierExpr).getExpressions()
        ConstructorCallExpression namedListCtorCallExpression = constructNamedListCtorCallExpression(argumentExpressionList)

        MethodCallExpression groupMethodCallExpression = callXWithLambda(groupMethodCallReceiver, "groupBy2", dataSourceExpression, namedListCtorCallExpression)

        this.currentGinqExpression.putNodeMetaData(__GROUP_BY, true)
        return groupMethodCallExpression
    }

    @Override
    MethodCallExpression visitOrderExpression(OrderExpression orderExpression) {
        DataSourceExpression dataSourceExpression = orderExpression.getNodeMetaData(__DATA_SOURCE_EXPRESSION)
        Expression orderMethodCallReceiver = orderExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        Expression ordersExpr = orderExpression.ordersExpr

        List<Expression> argumentExpressionList = ((ArgumentListExpression) ordersExpr).getExpressions()
        List<Expression> orderCtorCallExpressions = argumentExpressionList.stream().map(e -> {
            Expression target = e
            boolean asc = true
            if (e instanceof BinaryExpression && e.operation.type == Types.KEYWORD_IN) {
                target = e.leftExpression
                asc = 'asc' == e.rightExpression.text
            }

            LambdaExpression lambdaExpression = constructLambdaExpression(dataSourceExpression, target)

            return ctorX(ClassHelper.make(Queryable.Order.class), args(lambdaExpression, new ConstantExpression(asc)))
        }).collect(Collectors.toList())

        return callX(orderMethodCallReceiver, "orderBy", args(orderCtorCallExpressions))
    }

    @Override
    MethodCallExpression visitLimitExpression(LimitExpression limitExpression) {
        Expression limitMethodCallReceiver = limitExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        Expression offsetAndSizeExpr = limitExpression.offsetAndSizeExpr

        return callX(limitMethodCallReceiver, "limit", offsetAndSizeExpr)
    }

    @Override
    MethodCallExpression visitSelectExpression(SelectExpression selectExpression) {
        Expression selectMethodReceiver = selectExpression.getNodeMetaData(__METHOD_CALL_RECEIVER)
        DataSourceExpression dataSourceExpression = selectExpression.getNodeMetaData(__DATA_SOURCE_EXPRESSION)
        Expression projectionExpr = selectExpression.getProjectionExpr()

        List<Expression> expressionList = ((TupleExpression) projectionExpr).getExpressions()
        Expression lambdaCode
        if (expressionList.size() > 1) {
            ConstructorCallExpression namedListCtorCallExpression = constructNamedListCtorCallExpression(expressionList)
            lambdaCode = namedListCtorCallExpression
        } else {
            lambdaCode = expressionList.get(0)
        }

        return callXWithLambda(selectMethodReceiver, "select", dataSourceExpression, lambdaCode)
    }

    private static ConstructorCallExpression constructNamedListCtorCallExpression(List<Expression> expressionList) {
        int expressionListSize = expressionList.size()
        List<Expression> elementExpressionList = new ArrayList<>(expressionListSize)
        List<Expression> nameExpressionList = new ArrayList<>(expressionListSize)
        for (Expression e : expressionList) {
            Expression elementExpression = e
            Expression nameExpression = new ConstantExpression(e.text)

            if (e instanceof CastExpression) {
                elementExpression = e.expression
                nameExpression = new ConstantExpression(e.type.text)
            } else if (e instanceof PropertyExpression) {
                if (e.property instanceof ConstantExpression) {
                    elementExpression = e
                    nameExpression = new ConstantExpression(e.property.text)
                } else if (e.property instanceof GStringExpression) {
                    elementExpression = e
                    nameExpression = e.property
                }
            } else if (e instanceof MethodCallExpression) {
                elementExpression = e
                nameExpression = new ConstantExpression(e.methodAsString)
            }
            elementExpressionList << elementExpression
            nameExpressionList << nameExpression
        }

        ConstructorCallExpression namedListCtorCallExpression = ctorX(ClassHelper.make(NamedTuple.class), args(new ListExpression(elementExpressionList), new ListExpression(nameExpressionList)))
        return namedListCtorCallExpression
    }

    private Expression correctVariablesOfGinqExpression(DataSourceExpression dataSourceExpression, Expression expr) {
        boolean isGroup = isGroupByVisited()
        boolean isJoin = dataSourceExpression instanceof JoinExpression

        def correctVars = { Expression expression ->
            if (expression instanceof VariableExpression) {
                Expression transformedExpression = null

                if (isGroup) { //  groupby
                    // in #1, we will correct receiver of built-in aggregate functions
                    // the correct receiver is `__t.v2`, so we should not replace `__t` here
                    if (__T != expression.text) {
                        // replace `gk` in the groupby with `__t.v1.gk`, note: __t.v1 stores the group key
                        transformedExpression = propX(propX(new VariableExpression(__T), 'v1'), expression.text)
                    }
                } else if (isJoin) {
                    /*
                     * `n1`(`from` node) join `n2` join `n3`  will construct a join tree:
                     *
                     *  __t (join node)
                     *    |__ v2 (n3)
                     *    |__ v1 (join node)
                     *         |__ v2 (n2)
                     *         |__ v1 (n1) (`from` node)
                     *
                     * Note: `__t` is a tuple with 2 elements
                     * so  `n3`'s access path is `__t.v2`
                     * and `n2`'s access path is `__t.v1.v2`
                     * and `n1`'s access path is `__t.v1.v1`
                     *
                     * The following code shows how to construct the access path for variables
                     */
                    def prop = new VariableExpression(__T)
                    for (DataSourceExpression dse = dataSourceExpression;
                         null == transformedExpression && dse instanceof JoinExpression;
                         dse = dse.getNodeMetaData(__DATA_SOURCE_EXPRESSION)) {

                        DataSourceExpression otherDataSourceExpression = dse.getNodeMetaData(__DATA_SOURCE_EXPRESSION)
                        Expression firstAliasExpr = otherDataSourceExpression?.aliasExpr ?: EmptyExpression.INSTANCE
                        Expression secondAliasExpr = dse.aliasExpr

                        if (firstAliasExpr.text == expression.text && otherDataSourceExpression !instanceof JoinExpression) {
                            transformedExpression = propX(prop, 'v1')
                        } else if (secondAliasExpr.text == expression.text) {
                            transformedExpression = propX(prop, 'v2')
                        } else { // not found
                            prop = propX(prop, 'v1')
                        }
                    }
                }
                if (null != transformedExpression) {
                    return transformedExpression
                }
            } else if (expression instanceof MethodCallExpression) {
                // #1
                if (isGroup) { // groupby
                    if (expression.implicitThis) {
                        String methodName = expression.methodAsString
                        if ('count' == methodName && ((TupleExpression) expression.arguments).getExpressions().isEmpty()) {
                            expression.objectExpression = propX(new VariableExpression(__T), 'v2')
                            return expression
                        }
                    }
                }
            }

            return expression
        }

        // The synthetic lambda parameter `__t` represents the element from the result datasource of joining, e.g. `n1` innerJoin `n2`
        // The element from first datasource(`n1`) is referenced via `_t.v1`
        // and the element from second datasource(`n2`) is referenced via `_t.v2`
        expr = expr.transformExpression(new ExpressionTransformer() {
            @Override
            Expression transform(Expression expression) {
                Expression transformedExpression = correctVars(expression)
                if (transformedExpression !== expression) {
                    return transformedExpression
                }

                return expression.transformExpression(this)
            }
        })

        return correctVars(expr)
    }

    @Override
    Object visit(AbstractGinqExpression expression) {
        return expression.accept(this)
    }

    private MethodCallExpression callXWithLambda(Expression receiver, String methodName, DataSourceExpression dataSourceExpression, Expression lambdaCode) {
        LambdaExpression lambdaExpression = constructLambdaExpression(dataSourceExpression, lambdaCode)

        callXWithLambda(receiver, methodName, lambdaExpression)
    }

    private LambdaExpression constructLambdaExpression(DataSourceExpression dataSourceExpression, Expression lambdaCode) {
        Tuple2<String, Expression> paramNameAndLambdaCode = correctVariablesOfLambdaExpression(dataSourceExpression, lambdaCode)

        lambdaX(
                params(param(ClassHelper.DYNAMIC_TYPE, paramNameAndLambdaCode.v1)),
                stmt(paramNameAndLambdaCode.v2)
        )
    }

    private Tuple2<String, Expression> correctVariablesOfLambdaExpression(DataSourceExpression dataSourceExpression, Expression lambdaCode) {
        boolean isGroup = isGroupByVisited()

        String lambdaParamName
        if (dataSourceExpression instanceof JoinExpression || isGroup) {
            lambdaParamName = __T
            lambdaCode = correctVariablesOfGinqExpression(dataSourceExpression, lambdaCode)
        } else {
            lambdaParamName = dataSourceExpression.aliasExpr.text
        }

        return Tuple.tuple(lambdaParamName, lambdaCode)
    }

    private boolean isGroupByVisited() {
        return currentGinqExpression.getNodeMetaData(__GROUP_BY) ?: false
    }

    private static MethodCallExpression callXWithLambda(Expression receiver, String methodName, LambdaExpression lambdaExpression) {
        callX(
                receiver,
                methodName,
                lambdaExpression
        )
    }

    private static makeQueryableCollectionClassExpression() {
        new ClassExpression(ClassHelper.make(Queryable.class))
    }

    @Override
    SourceUnit getSourceUnit() {
        sourceUnit
    }

    private static final String __DATA_SOURCE_EXPRESSION = "__dataSourceExpression"
    private static final String __METHOD_CALL_RECEIVER = "__methodCallReceiver"
    private static final String __T = "__t"
    private static final String __GROUP_BY = "__GROUP_BY"
}

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
package org.apache.groovy.linq.dsl.expression;

import org.apache.groovy.linq.dsl.GinqVisitor;
import org.codehaus.groovy.ast.expr.Expression;

import java.util.Arrays;
import java.util.List;

/**
 * Represents join expression
 *
 * @since 4.0.0
 */
public class JoinExpression extends DataSourceExpression {
    private static final String CROSS_JOIN = "crossjoin";
    private static final List<String> JOIN_NAME_LIST = Arrays.asList("innerjoin", "leftjoin", "rightjoin", "fulljoin", CROSS_JOIN);
    private final String joinName;
    private OnExpression onExpression;

    public JoinExpression(String joinName, Expression aliasExpr, Expression dataSourceExpr) {
        super(aliasExpr, dataSourceExpr);
        this.joinName = joinName;
    }

    public static boolean isJoinExpression(String methodName) {
        return JOIN_NAME_LIST.contains(methodName);
    }

    public boolean isCrossJoin() {
        return CROSS_JOIN.equals(joinName);
    }

    @Override
    public <R> R accept(GinqVisitor<R> visitor) {
        return visitor.visitJoinExpression(this);
    }

    public String getJoinName() {
        return joinName;
    }

    public OnExpression getOnExpression() {
        return onExpression;
    }

    public void setOnExpression(OnExpression onExpression) {
        this.onExpression = onExpression;
    }
}

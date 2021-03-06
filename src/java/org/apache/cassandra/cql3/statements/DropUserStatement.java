/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements;

import org.apache.cassandra.auth.Auth;
import org.apache.cassandra.auth.AuthenticatedUser;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.apache.cassandra.exceptions.UnauthorizedException;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.transport.messages.ResultMessage;

public class DropUserStatement extends AuthenticationStatement
{
    private final String username;

    public DropUserStatement(String username)
    {
        this.username = username;
    }

    public void validate(ClientState state) throws RequestValidationException
    {
        // validate login here before checkAccess to avoid leaking user existence to anonymous users.
        state.ensureNotAnonymous();

        if (!Auth.isExistingUser(username))
            throw new InvalidRequestException(String.format("User %s doesn't exist", username));

        AuthenticatedUser user = state.getUser();
        //当前用户不能删除自己
        if (user != null && user.getName().equals(username))
            throw new InvalidRequestException("Users aren't allowed to DROP themselves");
    }

    public void checkAccess(ClientState state) throws UnauthorizedException
    {
        //只有超级用户才有drop user的权限
        if (!state.getUser().isSuper())
            throw new UnauthorizedException("Only superusers are allowed to perform DROP USER queries");
    }

    //事务问题同CreateUserStatement.execute(ClientState)
    public ResultMessage execute(ClientState state) throws RequestValidationException, RequestExecutionException
    {
        // clean up permissions after the dropped user.
        //删除permissions、users、credentials三个表中与username相关的记录
        DatabaseDescriptor.getAuthorizer().revokeAll(username);
        Auth.deleteUser(username);
        DatabaseDescriptor.getAuthenticator().drop(username);
        return null;
    }
}

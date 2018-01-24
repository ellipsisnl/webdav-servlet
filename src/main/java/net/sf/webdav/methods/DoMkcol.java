/*
 * Copyright 1999,2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.webdav.methods;

import java.io.IOException;
import java.util.Hashtable;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.sf.webdav.ITransaction;
import net.sf.webdav.IWebDAVStore;
import net.sf.webdav.StoredObject;
import net.sf.webdav.WebDAVStatus;
import net.sf.webdav.exceptions.AccessDeniedException;
import net.sf.webdav.exceptions.LockFailedException;
import net.sf.webdav.exceptions.WebDAVException;
import net.sf.webdav.locking.IResourceLocks;
import net.sf.webdav.locking.LockedObject;
import net.sf.webdav.util.URLUtil;

public class DoMkcol extends AbstractMethod {

	private static org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DoMkcol.class);

	private IWebDAVStore _store;
	private IResourceLocks _resourceLocks;
	private boolean _readOnly;

	public DoMkcol(IWebDAVStore store, IResourceLocks resourceLocks, boolean readOnly) {
		_store = store;
		_resourceLocks = resourceLocks;
		_readOnly = readOnly;
	}

	public void execute(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws IOException, LockFailedException {
		String path = getRelativePath(req);
		if(LOG.isDebugEnabled()) {
			LOG.debug("-- " + this.getClass().getName()+" "+path);
		}

		if (!_readOnly) {
			String parentPath = URLUtil.getParentPath(path);

			Hashtable<String, Integer> errorList = new Hashtable<String, Integer>();

			if (!checkLocks(transaction, req, resp, _resourceLocks, parentPath)) {
				// TODO remove
				LOG.debug("MkCol on locked resource (parentPath) not executable!"
						+ "\n Sending SC_FORBIDDEN (403) error response!");

				resp.sendError(WebDAVStatus.SC_FORBIDDEN);
				return;
			}

			String tempLockOwner = "doMkcol" + System.currentTimeMillis() + req.toString();

			if (_resourceLocks.lock(transaction, path, tempLockOwner, false, 0, TEMP_TIMEOUT, TEMPORARY)) {
				StoredObject parentSo, so = null;
				try {
					parentSo = _store.getStoredObject(transaction, parentPath);
					if (parentSo == null) {
						// parent not exists
						resp.sendError(WebDAVStatus.SC_CONFLICT);
						return;
					}
					if (parentPath != null && parentSo.isFolder()) {
						so = _store.getStoredObject(transaction, path);
						if (so == null) {
							_store.createFolder(transaction, path);
							resp.setStatus(WebDAVStatus.SC_CREATED);
						} else {
							// object already exists
							if (so.isNullResource()) {

								LockedObject nullResourceLo = _resourceLocks.getLockedObjectByPath(transaction, path);
								if (nullResourceLo == null) {
									resp.sendError(WebDAVStatus.SC_INTERNAL_SERVER_ERROR);
									return;
								}
								String nullResourceLockToken = nullResourceLo.getID();
								String[] lockTokens = getLockIdFromIfHeader(req);
								String lockToken = null;
								if (lockTokens != null)
									lockToken = lockTokens[0];
								else {
									resp.sendError(WebDAVStatus.SC_BAD_REQUEST);
									return;
								}
								if (lockToken.equals(nullResourceLockToken)) {
									so.setNullResource(false);
									so.setFolder(true);

									String[] nullResourceLockOwners = nullResourceLo.getOwner();
									String owner = null;
									if (nullResourceLockOwners != null)
										owner = nullResourceLockOwners[0];

									if (_resourceLocks.unlock(transaction, lockToken, owner)) {
										resp.setStatus(WebDAVStatus.SC_CREATED);
									} else {
										resp.sendError(WebDAVStatus.SC_INTERNAL_SERVER_ERROR);
									}

								} else {
									// TODO remove
									LOG.debug("MkCol on lock-null-resource with wrong lock-token!"
											+ "\n Sending multistatus error report!");

									errorList.put(path, WebDAVStatus.SC_LOCKED);
									sendReport(req, resp, errorList);
								}

							} else {
								String methodsAllowed = DeterminableMethod.determineMethodsAllowed(so);
								resp.addHeader(HEADER_ALLOW, methodsAllowed);
								resp.sendError(WebDAVStatus.SC_METHOD_NOT_ALLOWED);
							}
						}

					} else if (parentPath != null && parentSo.isResource()) {
						// TODO remove
						LOG.debug("MkCol on resource is not executable"
								+ "\n Sending SC_METHOD_NOT_ALLOWED (405) error response!");

						String methodsAllowed = DeterminableMethod.determineMethodsAllowed(parentSo);
						resp.addHeader(HEADER_ALLOW, methodsAllowed);
						resp.sendError(WebDAVStatus.SC_METHOD_NOT_ALLOWED);

					} else {
						resp.sendError(WebDAVStatus.SC_FORBIDDEN);
					}
				} catch (AccessDeniedException e) {
					resp.sendError(WebDAVStatus.SC_FORBIDDEN);
				} catch (WebDAVException e) {
					resp.sendError(WebDAVStatus.SC_INTERNAL_SERVER_ERROR);
				} finally {
					_resourceLocks.unlockTemporaryLockedObjects(transaction, path, tempLockOwner);
				}
			} else {
				resp.sendError(WebDAVStatus.SC_INTERNAL_SERVER_ERROR);
			}

		} else {
			resp.sendError(WebDAVStatus.SC_FORBIDDEN);
		}
	}

}

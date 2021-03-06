/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package com.act.lcms.db.model;

import com.act.lcms.db.io.DB;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class BaseDBModel<T extends BaseDBModel> {

  // These should be static, but static and abstract are incompatible! :(
  public abstract String getTableName();
  public abstract List<String> getAllFields();
  public abstract List<String> getInsertUpdateFields();
  protected abstract List<T> fromResultSet(ResultSet resultSet) throws SQLException, IOException, ClassNotFoundException;

  protected List<String> makeInsertUpdateFields() {
    List<String> allFields = getAllFields();
    return Collections.unmodifiableList(allFields.subList(1, allFields.size()));
  }

  protected String makeGetByIDQuery() {
    return StringUtils.join(new String[]{
        "SELECT", StringUtils.join(getAllFields(), ','),
        "from", getTableName(),
        "where id = ?",
    }, " ");
  }
  protected abstract String getGetByIDQuery();
  public T getById(DB db, Integer id) throws SQLException, IOException, ClassNotFoundException {
    try (PreparedStatement stmt = db.getConn().prepareStatement(getGetByIDQuery())) {
      stmt.setInt(1, id);
      try (ResultSet resultSet = stmt.executeQuery()) {
        return expectOneResult(resultSet, String.format("id = %d", id));
      }
    }
  }

  /**
   * Important: use this only when building constants to avoid the risk of SQL injection attacks.
   * @param field The field to select.
   * @return A query that binds a value to the specified field in a `WHERE` clause .
   */
  protected String makeGetQueryForSelectField(String field) {
    return StringUtils.join(new String[] {
        "SELECT", StringUtils.join(this.getAllFields(), ','),
        "from", this.getTableName(),
        String.format("where %s = ?", field),
    }, " ");
  }

  public abstract String getInsertQuery();
  protected String makeInsertQuery() {
    List<String> parameters = new ArrayList<>(getInsertUpdateFields().size());
    for (String field : getInsertUpdateFields()) {
      parameters.add("?");
    }
    return StringUtils.join(new String[] {
        "INSERT INTO", getTableName(), "(", StringUtils.join(getInsertUpdateFields(), ", "), ") VALUES (",
        StringUtils.join(parameters, ", "),
        ")",
    }, " ");
  }


  public abstract String getUpdateQuery();
  protected String makeUpdateQuery() {
    List<String> parameters = new ArrayList<>(getInsertUpdateFields().size());
    for (String field : getInsertUpdateFields()) {
      parameters.add(String.format("%s = ?", field));
    }
    return StringUtils.join(new String[] {
        "UPDATE", getTableName(), "SET",
        StringUtils.join(parameters, ", "),
        "WHERE",
        "id = ?",
    }, " ");
  }

  protected abstract void bindInsertOrUpdateParameters(PreparedStatement stmt, T parameterSource)
      throws SQLException, IOException;
  protected T insert(DB db, T toInsert) throws SQLException, IOException {
    return insert(db, toInsert, null);
  }

  protected T insert(DB db, T toInsert, String errMsg) throws SQLException, IOException {
    Connection conn = db.getConn();
    try (PreparedStatement stmt = conn.prepareStatement(getInsertQuery(), Statement.RETURN_GENERATED_KEYS)) {
      bindInsertOrUpdateParameters(stmt, toInsert);
      stmt.executeUpdate();
      try (ResultSet resultSet = stmt.getGeneratedKeys()) {
        if (resultSet.next()) {
          // Get auto-generated id.
          int id = resultSet.getInt(1);
          toInsert.setId(id);
          return toInsert;
        } else {
          // TODO: log error here.
          if (errMsg != null) {
            System.err.format("ERROR: %s\n", errMsg);
          } else {
            System.err.format("ERROR: could not retrieve autogenerated key for inserted row\n");
          }
          return null;
        }
      }
    }
  }

  public boolean update(DB db, T toUpdate) throws SQLException, IOException {
    Connection conn = db.getConn();
    try (PreparedStatement stmt = conn.prepareStatement(getUpdateQuery())) {
      bindInsertOrUpdateParameters(stmt, toUpdate);
      stmt.setInt(getInsertUpdateFields().size() + 1, toUpdate.getId());
      return stmt.executeUpdate() > 0;
    }
  }

  protected T expectOneResult(ResultSet resultSet, String queryErrStr) throws SQLException, IOException, ClassNotFoundException {
    List<T> results = this.fromResultSet(resultSet);
    if (results.size() > 1) {
      throw new SQLException("Found multiple results where one or zero expected: %s", queryErrStr);
    }
    if (results.size() == 0) {
      return null;
    }
    return results.get(0);
  }

  /* Use protected rather than private, as this class represents common attributes of all plate wells.  Extending
   * classes can/should have access to its fields, as its existence is simply an organizational convenience. */
  protected Integer id;

  public Integer getId() {
    return id;
  }

  protected void setId(Integer id) {
    this.id = id;
  }
}

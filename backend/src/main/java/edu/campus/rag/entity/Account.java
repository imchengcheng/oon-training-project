package edu.campus.rag.entity;

import jakarta.persistence.*;

@Entity(name = "Account")
@Table(name = "sys_user", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class Account extends BaseEntity {
  @Column(nullable = false, length = 64)
  public String username;

  @Column(nullable = false)
  public String passwordHash;

  public String realName;

  @Column(nullable = false, length = 16)
  public String roleType;

  @Column(unique = true)
  public String studentNo;

  public boolean enabled = true;
}

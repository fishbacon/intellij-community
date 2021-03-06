package com.intellij.execution.jshell.protocol;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * @author Eugene Zhuravlev
 * Date: 12-Jun-17
 */
@XmlRootElement
public class Request extends Message{
  private Command myCommand;
  private String myCodeText;

  @XmlEnum
  public enum Command{
    EVAL, DROP_STATE, EXIT
  }

  public Request() {
  }

  public Request(String uid, Command cmd, String codeText) {
    super(uid);
    myCommand = cmd;
    myCodeText = codeText;
  }

  public Command getCommand() {
    return myCommand;
  }

  @XmlElement
  public void setCommand(Command command) {
    myCommand = command;
  }

  public String getCodeText() {
    return myCodeText;
  }

  @XmlElement
  public void setCodeText(String codeText) {
    myCodeText = codeText;
  }
}

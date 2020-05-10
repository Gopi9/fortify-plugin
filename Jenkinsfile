pipeline {
    agent any
    stages {
	      stage('Build') {
            steps {
              sh "sudo mvn clean package"
            }
        }
        stage('Build') {
            steps {
              fortifyClean addJVMOptions: '', buildID: 'app', logFile: '', maxHeap: ''
            }
        }
    }
}

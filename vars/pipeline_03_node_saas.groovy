pipeline {
    agent any
    environment {
        COMMIT_ID = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
        DOCKER_TAG = "latest"
        REPO_NAME = "docker-build"
        GCP_REGION = "asia-south1"
        GCP_ARTIFACT_REGISTRY = "asia-south1-docker.pkg.dev"
        GCP_REPOSITORY = "${params.account}${params.deploy == 'prod' ? '-prod' : ''}"
        ENVIRONMENT = params.deploy.toString()

        PM1_EMAIL = 'megha.sharma@thecloudside.com'
        PM2_EMAIL = 'meghasharma282001@gmail.com'
        ADMIN_EMAIL = 'harshit.khandelwal@thecloudside.com'

        JOB_URL = "${env.JENKINS_URL}job/${env.JOB_NAME}/"
        PM1_USER = 'nisarg'
        PM2_USER = 'reena'
        ADMIN_USER = 'valay'
    }
    stages {
        stage('prepare') {
            steps {
                script {
                    setEnvironmentVariables(params)

                    echo "${env.BUILD_NUMBER}"
                    echo "${env.GIT_COMMIT}"
                    echo "${env.BRANCH_NAME}"
                    echo "${env.BUILD_TAG}"
                    echo "${env.JOB_NAME}"
                    echo "commit >> project${env.COMMIT_ID}"
                    sh "printenv"
                }
            }
            post {
                always {
                    script {
                        echo 'post build'
                    }
                }
                failure {
                    script {
                        echo 'send email'
                    }
                }
            }
        }

        stage('First Approval') {
            when {
                expression { params.deploy.toString() == "prod" }
            }
            steps {
                script {
                    sendApprovalRequest(
                        'First Approval',
                        PM1_EMAIL,
                        PM1_USER,
                        'additionalMessage1',
                        'ADDITIONAL_MESSAGE_1'
                    )
                }
            }
        }

        stage('Second Approval') {
            when {
                expression { params.deploy.toString() == "prod" }
            }
            steps {
                script {
                    def firstApprovalMessage = "<li><strong>First Approval:</strong><br>${env.ADDITIONAL_MESSAGE_1}</li>"
                    sendApprovalRequest(
                        'Second Approval',
                        PM2_EMAIL,
                        PM2_USER,
                        'additionalMessage2',
                        'ADDITIONAL_MESSAGE_2',
                        firstApprovalMessage
                    )
                }
            }
        }

        stage('Final Approval') {
            when {
                expression { params.deploy.toString() == "prod" }
            }
            steps {
                script {
                    def previousMessages = """
                        <li><strong>First Approval:</strong><br>${env.ADDITIONAL_MESSAGE_1}</li>
                        <li><strong>Second Approval:</strong><br>${env.ADDITIONAL_MESSAGE_2}</li>
                    """
                    sendApprovalRequest(
                        'Final Approval',
                        ADMIN_EMAIL,
                        ADMIN_USER,
                        'additionalMessageFinal',
                        'ADDITIONAL_MESSAGE_FINAL',
                        previousMessages
                    )

                    echo "Manager approved: ${env.ADDITIONAL_MESSAGE_FINAL}"
                    emailext(
                        subject: "Production Approval Confirmation",
                        body: """
                            <html>
                                <body style="font-family: Arial, sans-serif; line-height: 1.6;">
                                    <h2>Production Approval Confirmation</h2>
                                    <p>All approvals have been received. Below are the details:</p>
                                    <h3>Approval Messages:</h3>
                                    <ul>
                                        <li><strong>First Approval:</strong><br>${env.ADDITIONAL_MESSAGE_1}</li>
                                        <li><strong>Second Approval:</strong><br>${env.ADDITIONAL_MESSAGE_2}</li>
                                        <li><strong>Final Approval:</strong><br>${env.ADDITIONAL_MESSAGE_FINAL}</li>
                                    </ul>
                                    <h3>Jenkins UI:</h3>
                                    <p><a href="${JOB_URL}" style="background-color: #4CAF50; color: white; padding: 10px 20px; text-decoration: none; border-radius: 5px;">Please approve from Jenkins</a></p>
                                </body> 
                            </html>
                        """,
                        mimeType: 'text/html',
                        to: "${ADMIN_EMAIL}"
                    )
                }
            }
        }

        stage('build') {
            steps {
                script {
                    if (params.deploy.toString() == "prod") {
                        input message: "Deploy to production?", ok: "Deploy"
                    }
                    echo "docker build -t ${env.JOB_NAME}:${env.REPO_NAME}-v${env.BUILD_NUMBER}.0.0 ."
                    sh "docker build -t gcr.io/${G}/${REPO_NAME}:${DOCKER_TAG} ."
                }
            }
        }

        stage('push to gcr') {
            steps {
                script {
                    sh "docker image ls"
                    sh "gcloud auth configure-docker"
                    sh "docker push gcr.io/${G}/${REPO_NAME}:${DOCKER_TAG}"
                }
            }
        }
    }
}

def sendApprovalRequest(stageName, approverEmail, approverUser, messageName, additionalMessageEnvVar, previousMessages = '') {
    emailext(
        subject: "Approval Request - ${stageName}",
        body: """
            <html>
                <body style="font-family: Arial, sans-serif; line-height: 1.6;">
                    <h2>Approval Request - ${stageName}</h2>
                    <h3>Approval Messages:</h3>
                    <ul>
                        ${previousMessages}
                    </ul>
                    <p>${stageName} is required. Please review the changes and approve the job by clicking the link below:</p>
                    <p><a href="${JOB_URL}${BUILD_NUMBER}/input/" style="background-color: #4CAF50; color: white; padding: 10px 20px; text-decoration: none; border-radius: 5px;">Approve Job</a></p>
                </body>
            </html>
        """,
        mimeType: 'text/html',
        to: approverEmail
    )

    def approval = input message: "${stageName}", parameters: [
        text(defaultValue: '', description: "Additional Message from ${stageName}", name: messageName)
    ], submitter: approverUser

    env[additionalMessageEnvVar] = approval
}

def setEnvironmentVariables(Map params) {
    env.WORKSPACE = params.account
    env.REPO_NAME = params.name
    env.G = params.gcpProjectId
    env.SERVICE_NAME = params.name
}
